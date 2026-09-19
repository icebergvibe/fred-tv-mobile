package dev.fredol.open_tv.cast

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.CastStatusCodes
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import dev.fredol.open_tv.ExoPlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Owns the Cast SDK state for the whole app (device discovery, the cast
 * session, the remote media client) and the streaming pipeline that feeds
 * the receiver. Everything runs on the main thread (the Cast SDK and
 * MediaRouter require it; Flutter platform channels call us on it too).
 *
 * Talks to Flutter only through [Listener]; see [CastBridge].
 *
 * Single-viewer rule: the provider is only ever contacted by the FFmpeg
 * session inside [StreamPipeline]; the receiver only talks to
 * [LocalStreamServer]. [castStream] enforces the order: local player
 * released → previous pipeline stopped → new pipeline started → receiver
 * pointed at the local playlist.
 */
object CastManager {
    private const val TAG = "CastManager"
    private const val PROGRESS_PERIOD_MS = 1_000L
    private const val LOCAL_PLAYER_RELEASE_TIMEOUT_MS = 6_000L
    private const val RECEIVER_RELOAD_DELAY_MS = 2_000L
    private const val MAX_RECEIVER_RELOADS = 3
    private const val HLS_CONTENT_TYPE = "application/x-mpegURL"

    interface Listener {
        fun onRoutesChanged(routes: List<Map<String, Any?>>)
        fun onSessionChanged(session: Map<String, Any?>)
        fun onMediaStatus(status: Map<String, Any?>)
        fun onPipelineChanged(pipeline: Map<String, Any?>)
        fun onError(message: String)
    }

    /** Everything Flutter knows about a channel that the pipeline needs. */
    data class StreamRequest(
        val url: String,
        val title: String,
        val subtitle: String?,
        val imageUrl: String?,
        val isLive: Boolean,
        val startPositionMs: Long,
        val userAgent: String?,
        val referer: String?,
        val origin: String?,
    )

    var listener: Listener? = null

    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appContext: Context? = null
    private var castContext: CastContext? = null
    private var router: MediaRouter? = null
    private var selector: MediaRouteSelector? = null
    private var discoveryActive = false
    private var initFailure: String? = null

    private var session: CastSession? = null
    private var connecting = false
    private var lastSessionError: String? = null

    private val pipelineMutex = Mutex()
    private var pipeline: StreamPipeline? = null
    private var server: LocalStreamServer? = null
    private var castJob: Job? = null
    private var current: StreamRequest? = null
    private var receiverReloads = 0
    private var pipelineState: StreamPipeline.State = StreamPipeline.State.Idle

    // ---------------------------------------------------------------- init

    /** Returns true when the Cast SDK is usable on this device. Idempotent. */
    fun init(context: Context): Boolean {
        if (castContext != null) return true
        if (initFailure != null) return false
        val app = context.applicationContext
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app)
        if (availability != ConnectionResult.SUCCESS) {
            initFailure = "Google Play services unavailable (code $availability)"
            Log.w(TAG, initFailure!!)
            return false
        }
        return try {
            val ctx = CastContext.getSharedInstance(app)
            val sel = ctx.mergedSelector
                ?: MediaRouteSelector.Builder()
                    .addControlCategory(
                        CastMediaControlIntent.categoryForCast(
                            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
                        )
                    )
                    .build()
            ctx.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            appContext = app
            castContext = ctx
            selector = sel
            router = MediaRouter.getInstance(app)
            // A session may already exist (resumed after process restart).
            ctx.sessionManager.currentCastSession?.let { attachSession(it) }
            startDiscovery(active = false)
            true
        } catch (e: Exception) {
            initFailure = "Cast SDK init failed: ${e.message}"
            Log.e(TAG, initFailure!!, e)
            false
        }
    }

    fun isAvailable(): Boolean = castContext != null

    // ----------------------------------------------------------- discovery

    /**
     * Passive discovery is cheap and keeps the route list warm; active
     * scanning is what the SDK's own picker does while its dialog is open and
     * should only run while ours is.
     */
    fun startDiscovery(active: Boolean) {
        val r = router ?: return
        val sel = selector ?: return
        val flags = if (active) {
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY or MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
        } else {
            MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY
        }
        // addCallback with the same callback replaces the flags.
        r.addCallback(sel, routerCallback, flags)
        discoveryActive = active
        publishRoutes()
    }

    fun stopActiveDiscovery() {
        if (discoveryActive) startDiscovery(active = false)
    }

    fun routes(): List<Map<String, Any?>> {
        val r = router ?: return emptyList()
        val sel = selector ?: return emptyList()
        return r.routes
            .filter { !it.isDefault && it.matchesSelector(sel) }
            .map { route ->
                mapOf(
                    "id" to route.id,
                    "name" to route.name,
                    "description" to route.description,
                    "connected" to (route.connectionState == MediaRouter.RouteInfo.CONNECTION_STATE_CONNECTED),
                )
            }
    }

    private fun publishRoutes() {
        listener?.onRoutesChanged(routes())
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = publishRoutes()
    }

    // ------------------------------------------------------------- session

    /** Selecting a Cast route makes the SDK's SessionManager start a session. */
    fun connect(routeId: String): Boolean {
        val r = router ?: return false
        val route = r.routes.firstOrNull { it.id == routeId } ?: return false
        lastSessionError = null
        connecting = true
        publishSession()
        r.selectRoute(route)
        return true
    }

    fun disconnect() {
        val ctx = castContext ?: return
        stopCasting()
        // stopCasting=true also stops the receiver application.
        ctx.sessionManager.endCurrentSession(true)
    }

    fun sessionState(): Map<String, Any?> {
        val s = session
        val state = when {
            s != null && s.isConnected -> "connected"
            connecting || (s != null && s.isConnecting) -> "connecting"
            else -> "disconnected"
        }
        return mapOf(
            "state" to state,
            "device" to s?.castDevice?.friendlyName,
            "error" to lastSessionError,
        )
    }

    private fun publishSession() {
        listener?.onSessionChanged(sessionState())
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(s: CastSession) {
            connecting = true
            publishSession()
        }

        override fun onSessionStarted(s: CastSession, sessionId: String) {
            connecting = false
            attachSession(s)
        }

        override fun onSessionStartFailed(s: CastSession, error: Int) {
            connecting = false
            lastSessionError = "Could not connect: ${CastStatusCodes.getStatusCodeString(error)}"
            detachSession()
            listener?.onError(lastSessionError!!)
        }

        override fun onSessionEnding(s: CastSession) {}

        override fun onSessionEnded(s: CastSession, error: Int) {
            connecting = false
            if (error != CastStatusCodes.SUCCESS) {
                lastSessionError = "Session ended: ${CastStatusCodes.getStatusCodeString(error)}"
            }
            detachSession()
            // Nobody is watching any more: free the provider connection.
            stopCasting()
        }

        override fun onSessionResuming(s: CastSession, sessionId: String) {
            connecting = true
            publishSession()
        }

        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {
            connecting = false
            attachSession(s)
        }

        override fun onSessionResumeFailed(s: CastSession, error: Int) {
            connecting = false
            detachSession()
            stopCasting()
        }

        override fun onSessionSuspended(s: CastSession, reason: Int) {
            // Network hiccup; SDK will try to resume. Keep the session object.
            publishSession()
        }
    }

    private fun attachSession(s: CastSession) {
        session = s
        s.remoteMediaClient?.let { rmc ->
            rmc.registerCallback(mediaCallback)
            rmc.addProgressListener(progressListener, PROGRESS_PERIOD_MS)
        }
        publishSession()
        publishMediaStatus()
    }

    private fun detachSession() {
        session?.remoteMediaClient?.let { rmc ->
            rmc.unregisterCallback(mediaCallback)
            rmc.removeProgressListener(progressListener)
        }
        session = null
        publishSession()
        publishMediaStatus()
    }

    // ------------------------------------------------------------ pipeline

    /**
     * Casts a channel through the local pipeline. Any previous cast (or local
     * playback) is torn down first so only one upstream connection ever
     * exists.
     */
    fun castStream(request: StreamRequest) {
        val ctx = appContext ?: return
        if (session?.remoteMediaClient == null) {
            listener?.onError("Not connected to a cast device")
            return
        }
        castJob?.cancel()
        castJob = scope.launch {
            pipelineMutex.withLock {
                current = request
                receiverReloads = 0
                awaitLocalPlayerReleased()
                val pipe = pipeline ?: StreamPipeline(File(ctx.cacheDir, "cast_hls")).also { pipeline = it }
                if (!pipe.stop()) {
                    // The old FFmpeg still holds the provider's only slot;
                    // starting another would be a second upstream connection.
                    fail(StreamPipeline.STUCK_MESSAGE)
                    return@withLock
                }
                val host = LocalStreamServer.lanAddress(ctx)
                if (host == null) {
                    fail("No Wi-Fi address found; the Chromecast can't reach this phone")
                    return@withLock
                }
                val srv = server ?: runCatching { LocalStreamServer.start(pipe.playlistFile.parentFile!!) }
                    .getOrElse { e ->
                        fail("Could not start local server: ${e.message}")
                        return@withLock
                    }
                    .also { server = it }
                CastService.start(ctx, request.title, session?.castDevice?.friendlyName)
                pipe.start(
                    StreamPipeline.Request(
                        url = request.url,
                        userAgent = request.userAgent?.takeIf { it.isNotBlank() } ?: defaultUserAgent(ctx),
                        referer = request.referer,
                        origin = request.origin,
                        isLive = request.isLive,
                        startMs = request.startPositionMs,
                    ),
                ) { state -> if (current === request) onPipelineState(state, srv, host) }
            }
        }
    }

    /** Same shape as ExoPlayer's default UA so the provider sees one client. */
    private fun defaultUserAgent(ctx: Context): String {
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
        return "Fred TV/$version (Linux;Android ${Build.VERSION.RELEASE}) FredTV"
    }

    /** Stops the pipeline and the receiver's playback; keeps the session. */
    fun stopCasting() {
        castJob?.cancel()
        castJob = null
        val pipe = pipeline
        current = null
        appContext?.let { CastService.stop(it) }
        runCatching { session?.remoteMediaClient?.stop() }
        if (pipe != null && (pipe.isRunning || pipelineState != StreamPipeline.State.Idle)) {
            scope.launch {
                pipelineMutex.withLock {
                    if (!pipe.stop()) listener?.onError(StreamPipeline.STUCK_MESSAGE)
                }
            }
        }
    }

    private suspend fun awaitLocalPlayerReleased() {
        val deadline = System.currentTimeMillis() + LOCAL_PLAYER_RELEASE_TIMEOUT_MS
        while (ExoPlayerView.active != null && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        if (ExoPlayerView.active != null) {
            Log.w(TAG, "Local player still alive after ${LOCAL_PLAYER_RELEASE_TIMEOUT_MS}ms; casting anyway")
        }
    }

    private fun onPipelineState(state: StreamPipeline.State, srv: LocalStreamServer, host: String) {
        pipelineState = state
        publishPipeline()
        when (state) {
            is StreamPipeline.State.Ready -> {
                receiverReloads = 0
                loadLocalPlaylist(srv, host)
            }
            is StreamPipeline.State.Failed -> {
                appContext?.let { CastService.stop(it) }
                runCatching { session?.remoteMediaClient?.stop() }
                listener?.onError("Stream failed: ${state.message}")
            }
            else -> {}
        }
    }

    private fun loadLocalPlaylist(srv: LocalStreamServer, host: String) {
        val req = current ?: return
        val pipe = pipeline ?: return
        val url = "${srv.baseUrl(host)}/${StreamPipeline.PLAYLIST_NAME}"
        Log.i(TAG, "Loading $url on receiver (mode=${pipe.currentMode})")
        loadOnReceiver(
            url = url,
            title = req.title,
            subtitle = req.subtitle,
            imageUrl = req.imageUrl,
            isLive = req.isLive,
            contentType = HLS_CONTENT_TYPE,
            startPositionMs = 0L,
            durationMs = if (req.isLive) null else pipe.info.durationMs,
        )
    }

    private fun fail(message: String) {
        Log.w(TAG, message)
        pipelineState = StreamPipeline.State.Failed(message)
        publishPipeline()
        appContext?.let { CastService.stop(it) }
        listener?.onError(message)
    }

    fun pipelineStatus(): Map<String, Any?> {
        val pipe = pipeline
        val s = pipelineState
        val (name, message, attempt) = when (s) {
            StreamPipeline.State.Idle -> Triple("idle", null, 0)
            is StreamPipeline.State.Starting -> Triple("starting", null, s.attempt)
            is StreamPipeline.State.Ready -> Triple("ready", null, 0)
            is StreamPipeline.State.Finished -> Triple("finished", null, 0)
            is StreamPipeline.State.Reconnecting -> Triple("reconnecting", s.reason, s.attempt)
            is StreamPipeline.State.Failed -> Triple("failed", s.message, 0)
            StreamPipeline.State.Stopped -> Triple("idle", null, 0)
        }
        return mapOf(
            "state" to name,
            "message" to message,
            "attempt" to attempt,
            "mode" to when (pipe?.currentMode) {
                StreamPipeline.Mode.REMUX -> "remux"
                StreamPipeline.Mode.TRANSCODE_HW -> "transcode_hw"
                StreamPipeline.Mode.TRANSCODE_SW -> "transcode_sw"
                null -> null
            },
            "videoCodec" to pipe?.info?.videoCodec,
            "audioCodec" to pipe?.info?.audioCodec,
        )
    }

    fun pipelineLog(): List<String> = pipeline?.logTail() ?: emptyList()

    private fun publishPipeline() {
        listener?.onPipelineChanged(pipelineStatus())
    }

    // --------------------------------------------------------------- media

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            publishMediaStatus()
            handleReceiverIdle()
        }

        override fun onMetadataUpdated() = publishMediaStatus()
    }

    private val progressListener = RemoteMediaClient.ProgressListener { _, _ -> publishMediaStatus() }

    /**
     * The receiver dropped the stream. If the user stopped it, release the
     * provider; if it just choked (live HLS hiccup, pipeline restart) and the
     * pipeline is healthy, point it at the playlist again.
     */
    private fun handleReceiverIdle() {
        val status = session?.remoteMediaClient?.mediaStatus ?: return
        if (status.playerState != MediaStatus.PLAYER_STATE_IDLE) return
        if (current == null) return
        val healthy = pipelineState is StreamPipeline.State.Ready ||
            pipelineState is StreamPipeline.State.Finished
        if (!healthy) return
        when (status.idleReason) {
            MediaStatus.IDLE_REASON_CANCELED -> stopCasting()
            MediaStatus.IDLE_REASON_ERROR,
            MediaStatus.IDLE_REASON_INTERRUPTED,
            MediaStatus.IDLE_REASON_FINISHED -> {
                if (current?.isLive != true && status.idleReason == MediaStatus.IDLE_REASON_FINISHED) {
                    stopCasting()
                    return
                }
                if (receiverReloads >= MAX_RECEIVER_RELOADS) {
                    fail("Receiver keeps rejecting the stream")
                    return
                }
                receiverReloads++
                val srv = server ?: return
                val host = LocalStreamServer.lanAddress(appContext ?: return) ?: return
                handler.postDelayed({
                    if (current != null && pipelineState is StreamPipeline.State.Ready) {
                        loadLocalPlaylist(srv, host)
                    }
                }, RECEIVER_RELOAD_DELAY_MS)
            }
        }
    }

    /** Loads an arbitrary URL on the receiver (used for the test video). */
    fun load(
        url: String,
        title: String,
        subtitle: String?,
        imageUrl: String?,
        isLive: Boolean,
        contentType: String,
        startPositionMs: Long,
    ) {
        // Whatever the pipeline was feeding is replaced; release the provider.
        stopCasting()
        loadOnReceiver(url, title, subtitle, imageUrl, isLive, contentType, startPositionMs, null)
    }

    private fun loadOnReceiver(
        url: String,
        title: String,
        subtitle: String?,
        imageUrl: String?,
        isLive: Boolean,
        contentType: String,
        startPositionMs: Long,
        durationMs: Long?,
    ) {
        val rmc = session?.remoteMediaClient
        if (rmc == null) {
            listener?.onError("Not connected to a cast device")
            return
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            if (!subtitle.isNullOrEmpty()) putString(MediaMetadata.KEY_SUBTITLE, subtitle)
            if (!imageUrl.isNullOrEmpty()) {
                runCatching { addImage(WebImage(Uri.parse(imageUrl))) }
            }
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(if (isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType)
            .setMetadata(metadata)
            .apply { if (durationMs != null && durationMs > 0) setStreamDuration(durationMs) }
            .build()
        val request = MediaLoadRequestData.Builder()
            .setMediaInfo(info)
            .setAutoplay(true)
            .setCurrentTime(if (isLive) 0L else startPositionMs)
            .build()
        rmc.load(request).setResultCallback { result ->
            if (!result.status.isSuccess) {
                val msg = "Receiver refused to load stream: ${CastStatusCodes.getStatusCodeString(result.status.statusCode)}"
                Log.w(TAG, msg)
                listener?.onError(msg)
            }
            publishMediaStatus()
        }
    }

    fun play() { session?.remoteMediaClient?.play() }
    fun pause() { session?.remoteMediaClient?.pause() }

    /** Stop from the UI: receiver playback and the pipeline both end. */
    fun stop() { stopCasting() }

    fun seekTo(positionMs: Long) {
        val pipe = pipeline
        val offset = if (current != null && pipe != null) pipe.offsetMs else 0L
        session?.remoteMediaClient?.seek(
            MediaSeekOptions.Builder().setPosition((positionMs - offset).coerceAtLeast(0L)).build()
        )
    }

    /** Device volume, 0.0 to 1.0. */
    fun setVolume(volume: Double) {
        val s = session ?: return
        runCatching { s.volume = volume.coerceIn(0.0, 1.0) }
            .onFailure { Log.w(TAG, "setVolume failed", it) }
    }

    fun setMuted(muted: Boolean) {
        val s = session ?: return
        runCatching { s.isMute = muted }.onFailure { Log.w(TAG, "setMuted failed", it) }
    }

    fun mediaStatus(): Map<String, Any?> {
        val s = session
        val rmc = s?.remoteMediaClient
        val status: MediaStatus? = rmc?.mediaStatus
        val playerState = when (status?.playerState) {
            MediaStatus.PLAYER_STATE_IDLE -> "idle"
            MediaStatus.PLAYER_STATE_LOADING -> "loading"
            MediaStatus.PLAYER_STATE_BUFFERING -> "buffering"
            MediaStatus.PLAYER_STATE_PLAYING -> "playing"
            MediaStatus.PLAYER_STATE_PAUSED -> "paused"
            else -> "idle"
        }
        val idleReason = when (status?.idleReason) {
            MediaStatus.IDLE_REASON_FINISHED -> "finished"
            MediaStatus.IDLE_REASON_CANCELED -> "cancelled"
            MediaStatus.IDLE_REASON_INTERRUPTED -> "interrupted"
            MediaStatus.IDLE_REASON_ERROR -> "error"
            else -> "none"
        }
        val req = current
        val pipe = pipeline
        // Through the pipeline the receiver's timeline starts at the -ss offset
        // and it can't know the real duration of a live-style playlist.
        val piped = req != null && pipe != null
        val isLive = if (piped) req!!.isLive else (rmc?.isLiveStream ?: false)
        val offset = if (piped) pipe!!.offsetMs else 0L
        val durationMs = when {
            rmc == null || isLive -> -1L
            piped -> pipe!!.info.durationMs ?: -1L
            else -> rmc.streamDuration
        }
        return mapOf(
            "hasMedia" to (status?.mediaInfo != null),
            "playerState" to playerState,
            "idleReason" to idleReason,
            "positionMs" to ((rmc?.approximateStreamPosition ?: 0L) + offset),
            "durationMs" to durationMs,
            "isLive" to isLive,
            "volume" to (runCatching { s?.volume }.getOrNull() ?: 1.0),
            "muted" to (runCatching { s?.isMute }.getOrNull() ?: false),
            "contentId" to status?.mediaInfo?.contentId,
            "title" to (req?.title ?: status?.mediaInfo?.metadata?.getString(MediaMetadata.KEY_TITLE)),
        )
    }

    private fun publishMediaStatus() {
        listener?.onMediaStatus(mediaStatus())
    }

    // ---------------------------------------------------------------- debug

    /** Runs server + pipeline without a Cast session (debug builds only). */
    fun debugStartPipeline(
        context: Context,
        url: String,
        isLive: Boolean,
        startMs: Long,
        forceMode: StreamPipeline.Mode? = null,
    ) {
        val ctx = context.applicationContext.also { appContext = appContext ?: it }
        scope.launch {
            pipelineMutex.withLock {
                val pipe = pipeline ?: StreamPipeline(File(ctx.cacheDir, "cast_hls")).also { pipeline = it }
                if (!pipe.stop()) {
                    Log.e(TAG, StreamPipeline.STUCK_MESSAGE)
                    return@withLock
                }
                val srv = server ?: LocalStreamServer.start(pipe.playlistFile.parentFile!!).also { server = it }
                val host = LocalStreamServer.lanAddress(ctx) ?: "127.0.0.1"
                Log.i("CastDebug", "playlist: ${srv.baseUrl(host)}/${StreamPipeline.PLAYLIST_NAME}")
                pipe.start(
                    StreamPipeline.Request(
                        url = url,
                        userAgent = defaultUserAgent(ctx),
                        referer = null,
                        origin = null,
                        isLive = isLive,
                        startMs = startMs,
                        readRealtime = isLive && !url.startsWith("http", ignoreCase = true),
                        forceMode = forceMode,
                    ),
                ) { state ->
                    pipelineState = state
                    Log.i("CastDebug", "pipeline: $state mode=${pipe.currentMode} info=${pipe.info}")
                    if (state is StreamPipeline.State.Reconnecting || state is StreamPipeline.State.Failed) {
                        pipe.logTail().takeLast(25).forEach { Log.i("CastDebug", "  | $it") }
                    }
                }
            }
        }
    }

    fun debugStopPipeline() {
        val pipe = pipeline ?: return
        scope.launch {
            pipelineMutex.withLock {
                Log.i(TAG, if (pipe.stop()) "Pipeline stopped" else StreamPipeline.STUCK_MESSAGE)
            }
        }
    }

    /** Run on the main thread (Cast SDK requirement). */
    fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }
}
