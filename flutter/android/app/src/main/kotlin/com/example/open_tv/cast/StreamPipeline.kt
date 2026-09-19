package dev.fredol.open_tv.cast

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFmpegSession
import com.antonkarpenko.ffmpegkit.Level
import com.antonkarpenko.ffmpegkit.ReturnCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.ArrayDeque

/**
 * Turns one IPTV stream into an HLS playlist in [workDir] using FFmpeg.
 *
 * Exactly one FFmpeg session exists at a time: it is the only thing that ever
 * connects to the provider, so the single-viewer limit is respected as long
 * as callers [stop] before they [start] again (and both are sequential).
 *
 * Mode selection is done without an extra probe connection: FFmpeg starts in
 * REMUX mode (-c copy), its input dump tells us the codecs within a second or
 * two, and if the receiver can't play them the session is cancelled and
 * restarted in a transcode mode. Hardware (MediaCodec) encoding is tried
 * first; if that fails to start, x264 in software takes over.
 *
 * All state lives on the main thread; FFmpeg callbacks are posted there.
 */
class StreamPipeline(private val workDir: File) {

    enum class Mode { REMUX, TRANSCODE_HW, TRANSCODE_SW }

    data class Request(
        val url: String,
        val userAgent: String?,
        val referer: String?,
        val origin: String?,
        val isLive: Boolean,
        val startMs: Long,
        /** Skip detection and start in this mode (e.g. cached from last time). */
        val initialMode: Mode? = null,
        /** Read the input at its native rate (-re); makes a file act live. */
        val readRealtime: Boolean = false,
        /** Debug: force this mode and skip HW→SW fallback logic. */
        val forceMode: Mode? = null,
        val videoCodecsOk: Set<String> = setOf("h264"),
        val audioCodecsOk: Set<String> = setOf("aac"),
    )

    data class StreamInfo(
        var videoCodec: String? = null,
        var audioCodec: String? = null,
        var durationMs: Long? = null,
    )

    sealed class State {
        object Idle : State()
        data class Starting(val mode: Mode, val attempt: Int) : State()
        data class Ready(val mode: Mode, val info: StreamInfo) : State()
        /** FFmpeg reached the end of a VOD input; the playlist is complete. */
        data class Finished(val mode: Mode) : State()
        data class Reconnecting(val attempt: Int, val delayMs: Long, val reason: String) : State()
        data class Failed(val message: String) : State()
        object Stopped : State()
    }

    fun interface Listener {
        fun onState(state: State)
    }

    companion object {
        private const val TAG = "StreamPipeline"
        const val PLAYLIST_NAME = "index.m3u8"
        private const val SEGMENT_SECONDS = 2
        private const val LIVE_LIST_SIZE = 10
        private const val VOD_LIST_SIZE = 120 // ~4 minutes of back-buffer
        private const val READY_SEGMENTS = 2
        private const val READY_POLL_MS = 300L
        private const val READY_TIMEOUT_MS = 45_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        const val STUCK_MESSAGE =
            "FFmpeg did not stop; force-stop the app to free the stream connection"
        private const val LOG_TAIL = 200
        // Same cadence as the local player's reconnect logic.
        private val RECONNECT_DELAYS_MS = longArrayOf(1_000L, 3_000L, 5_000L)
        private const val MAX_RECONNECTS = 5
        private val STREAM_LINE = Regex("""Stream #0:\d+.*?: (Video|Audio): ([A-Za-z0-9_]+)""")
        private val DURATION_LINE = Regex("""Duration: (\d+):(\d+):(\d+)\.(\d+)""")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val logTail = ArrayDeque<String>(LOG_TAIL)
    // FFmpeg emits one line as several av_log calls; parse only whole lines.
    private val lineBuffer = StringBuilder()

    private var request: Request? = null
    private var listener: Listener? = null
    private var session: FFmpegSession? = null
    private var generation = 0
    private var mode = Mode.REMUX
    private var attempt = 0
    private var detected = false
    private var ready = false
    private var stopping = false
    private var stopDeferred: CompletableDeferred<Unit>? = null
    private var afterCancel: (() -> Unit)? = null
    private var readyPoll: Runnable? = null
    private var readyDeadline = 0L
    private var reconnect: Runnable? = null
    private var cancelWatchdog: Runnable? = null

    var state: State = State.Idle
        private set(value) {
            field = value
            listener?.onState(value)
        }

    var info = StreamInfo()
        private set

    val currentMode: Mode get() = mode
    val playlistFile: File get() = File(workDir, PLAYLIST_NAME)
    val isRunning: Boolean get() = session != null

    /** Offset of the HLS timeline against the source (the -ss applied for VOD). */
    var offsetMs: Long = 0L
        private set

    fun logTail(): List<String> = synchronized(logTail) { logTail.toList() }

    // ------------------------------------------------------------ control

    /** Caller must have [stop]ped any previous run first. */
    fun start(request: Request, listener: Listener) {
        check(session == null) { "pipeline already running" }
        this.request = request
        this.listener = listener
        stopping = false
        attempt = 0
        offsetMs = if (request.isLive) 0L else request.startMs
        synchronized(logTail) { logTail.clear() }
        lineBuffer.setLength(0)
        launch(request.forceMode ?: request.initialMode ?: Mode.REMUX)
    }

    /**
     * Cancels FFmpeg and waits until it has actually exited (socket closed).
     * Safe to call concurrently: a second caller joins the stop in progress.
     *
     * Returns false if FFmpeg is still running after a per-session cancel and
     * a global one: the provider connection is then still held, so callers
     * must not start anything else. [session] stays set so [isRunning] keeps
     * telling the truth; a later stop() tries again.
     */
    suspend fun stop(): Boolean = withContext(NonCancellable) {
        val running = session
        if (running == null) {
            cancelPending()
            clearWorkDir()
            state = State.Stopped
            return@withContext true
        }
        val deferred = stopDeferred ?: CompletableDeferred<Unit>().also { d ->
            stopDeferred = d
            stopping = true
            afterCancel = null
            cancelPending()
            running.cancel()
        }
        var exited = withTimeoutOrNull(STOP_TIMEOUT_MS) { deferred.await() } != null
        if (!exited && stopDeferred === deferred) {
            Log.w(TAG, "FFmpeg ignored the session cancel for ${STOP_TIMEOUT_MS}ms; cancelling globally")
            FFmpegKit.cancel()
            exited = withTimeoutOrNull(STOP_TIMEOUT_MS) { deferred.await() } != null
        }
        if (stopDeferred === deferred) {
            stopDeferred = null
            if (exited) {
                session = null
                clearWorkDir()
                state = State.Stopped
            } else {
                Log.e(TAG, STUCK_MESSAGE)
                appendLog("!! $STUCK_MESSAGE")
                state = State.Failed(STUCK_MESSAGE)
            }
        }
        exited
    }

    /** Drops every scheduled relaunch/poll without touching FFmpeg's own callbacks. */
    private fun cancelPending() {
        stopReadyPoll()
        reconnect?.let { handler.removeCallbacks(it) }
        reconnect = null
        cancelWatchdog?.let { handler.removeCallbacks(it) }
        cancelWatchdog = null
    }

    // ------------------------------------------------------------- launch

    private fun launch(newMode: Mode) {
        val req = request ?: return
        val gen = ++generation
        mode = newMode
        detected = false
        ready = false
        // The transcode command is built from the codecs the remux attempt
        // detected; only a fresh remux attempt starts from a blank slate.
        if (newMode == Mode.REMUX) info = StreamInfo()
        clearWorkDir()
        workDir.mkdirs()
        val args = buildArgs(req, newMode)
        appendLog("$ ffmpeg ${args.joinToString(" ")}")
        state = State.Starting(newMode, attempt)
        session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { s -> handler.post { onComplete(gen, s) } },
            { log ->
                val msg = log.message ?: return@executeWithArgumentsAsync
                if (log.level.value <= Level.AV_LOG_INFO.value) {
                    handler.post { onLog(gen, msg) }
                }
            },
            null,
        )
        startReadyPoll(gen)
    }

    private fun restartInMode(newMode: Mode, gen: Int) {
        val running = session ?: return
        // Never overlap: the new session may only start once this one has
        // exited and released its upstream connection.
        afterCancel = { launch(newMode) }
        generation++ // ignore further callbacks from the old session
        stopReadyPoll()
        cancelSession(running)
        Log.i(TAG, "Restarting pipeline in $newMode (gen $gen)")
    }

    /**
     * Cancels a session we intend to replace, and watches that it really
     * exits: a session that ignores the cancel would hold the provider's
     * only slot while we sit in Starting forever.
     */
    private fun cancelSession(running: FFmpegSession) {
        running.cancel()
        cancelWatchdog?.let { handler.removeCallbacks(it) }
        val watchdog = object : Runnable {
            var escalated = false
            override fun run() {
                if (session !== running) return // it exited; onComplete handled it
                if (!escalated) {
                    escalated = true
                    Log.w(TAG, "FFmpeg ignored the session cancel for ${STOP_TIMEOUT_MS}ms; cancelling globally")
                    FFmpegKit.cancel()
                    handler.postDelayed(this, STOP_TIMEOUT_MS)
                    return
                }
                cancelWatchdog = null
                afterCancel = null
                Log.e(TAG, STUCK_MESSAGE)
                appendLog("!! $STUCK_MESSAGE")
                state = State.Failed(STUCK_MESSAGE)
            }
        }
        cancelWatchdog = watchdog
        handler.postDelayed(watchdog, STOP_TIMEOUT_MS)
    }

    // ---------------------------------------------------------- callbacks

    private fun onLog(gen: Int, message: String) {
        lineBuffer.append(message)
        val lines = mutableListOf<String>()
        while (true) {
            val nl = lineBuffer.indexOf("\n")
            if (nl < 0) break
            lines += lineBuffer.substring(0, nl)
            lineBuffer.delete(0, nl + 1)
        }
        for (line in lines) {
            appendLog(line)
            if (gen != generation) continue
            STREAM_LINE.find(line)?.let { m ->
                val codec = m.groupValues[2].lowercase()
                if (m.groupValues[1] == "Video" && info.videoCodec == null) info.videoCodec = codec
                if (m.groupValues[1] == "Audio" && info.audioCodec == null) info.audioCodec = codec
            }
            if (info.durationMs == null) {
                DURATION_LINE.find(line)?.let { m ->
                    val (h, min, s, frac) = m.destructured
                    val fracMs = (frac + "00").take(3).toLong()
                    info.durationMs = ((h.toLong() * 3600 + min.toLong() * 60 + s.toLong()) * 1000) + fracMs
                }
            }
            if (!detected && (line.startsWith("Output #0") || line.contains("Stream mapping:"))) {
                detected = true
                onInputDetected(gen)
            }
        }
    }

    private fun onInputDetected(gen: Int) {
        val req = request ?: return
        Log.i(TAG, "Input: video=${info.videoCodec} audio=${info.audioCodec} duration=${info.durationMs}")
        if (mode != Mode.REMUX || req.forceMode != null) return
        val videoOk = info.videoCodec == null || info.videoCodec in req.videoCodecsOk
        val audioOk = info.audioCodec == null || info.audioCodec in req.audioCodecsOk
        if (!videoOk || !audioOk) {
            restartInMode(Mode.TRANSCODE_HW, gen)
        }
    }

    private fun onComplete(gen: Int, s: FFmpegSession) {
        // Only the session we are tracking frees the slot; a completion from
        // an older one (already replaced) must not make us think the current
        // one has exited.
        val isCurrent = session === s
        if (isCurrent) {
            cancelWatchdog?.let { handler.removeCallbacks(it) }
            cancelWatchdog = null
            session = null
        }
        if (stopping) {
            if (isCurrent) stopDeferred?.complete(Unit)
            return
        }
        val pending = afterCancel
        if (pending != null && isCurrent) {
            afterCancel = null
            pending()
            return
        }
        if (gen != generation || !isCurrent) return
        stopReadyPoll()
        val code = s.returnCode
        val req = request ?: return
        when {
            ReturnCode.isCancel(code) -> state = State.Stopped
            // A VOD input that FFmpeg finished (possibly before the readiness
            // poll ever ran, for short or fast inputs).
            ReturnCode.isSuccess(code) && !req.isLive && segmentCount() > 0 -> {
                if (!ready) {
                    ready = true
                    state = State.Ready(mode, info)
                }
                state = State.Finished(mode)
            }
            // Hardware encoder failed to start: fall back to software once.
            mode == Mode.TRANSCODE_HW && !ready -> {
                appendLog("!! hardware encoder failed, falling back to software")
                launch(Mode.TRANSCODE_SW)
            }
            else -> scheduleReconnect(lastError())
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (attempt >= MAX_RECONNECTS) {
            state = State.Failed(reason)
            return
        }
        val delay = RECONNECT_DELAYS_MS[attempt.coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        attempt++
        state = State.Reconnecting(attempt, delay, reason)
        val relaunch = Runnable {
            reconnect = null
            if (!stopping && session == null) launch(mode)
        }
        reconnect = relaunch
        handler.postDelayed(relaunch, delay)
    }

    // ---------------------------------------------------------- readiness

    private fun startReadyPoll(gen: Int) {
        stopReadyPoll()
        readyDeadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        val poll = object : Runnable {
            override fun run() {
                if (gen != generation || session == null) return
                if (segmentCount() >= READY_SEGMENTS) {
                    ready = true
                    attempt = 0
                    readyPoll = null
                    state = State.Ready(mode, info)
                    return
                }
                if (System.currentTimeMillis() > readyDeadline) {
                    appendLog("!! no segments after ${READY_TIMEOUT_MS / 1000}s")
                    afterCancel = { scheduleReconnect("Stream produced no data") }
                    generation++
                    session?.let { cancelSession(it) }
                    readyPoll = null
                    return
                }
                handler.postDelayed(this, READY_POLL_MS)
            }
        }
        readyPoll = poll
        handler.postDelayed(poll, READY_POLL_MS)
    }

    private fun stopReadyPoll() {
        readyPoll?.let { handler.removeCallbacks(it) }
        readyPoll = null
    }

    private fun segmentCount(): Int {
        val f = playlistFile
        if (!f.isFile) return 0
        return runCatching { f.readLines().count { it.startsWith("#EXTINF") } }.getOrDefault(0)
    }

    // ------------------------------------------------------------- ffmpeg

    private fun buildArgs(req: Request, mode: Mode): List<String> {
        val args = mutableListOf(
            "-hide_banner", "-nostdin", "-nostats", "-loglevel", "info", "-y",
            "-fflags", "+genpts+discardcorrupt",
        )
        // HTTP-protocol options; FFmpeg rejects them for other inputs (file:).
        if (req.url.startsWith("http", ignoreCase = true)) {
            req.userAgent?.takeIf { it.isNotBlank() }?.let { args += listOf("-user_agent", it) }
            val headers = buildString {
                req.referer?.takeIf { it.isNotBlank() }?.let { append("Referer: $it\r\n") }
                req.origin?.takeIf { it.isNotBlank() }?.let { append("Origin: $it\r\n") }
            }
            if (headers.isNotEmpty()) args += listOf("-headers", headers)
            args += listOf(
                "-reconnect", "1", "-reconnect_streamed", "1",
                "-reconnect_on_network_error", "1", "-reconnect_delay_max", "3",
            )
        }
        if (req.isLive) {
            // Faster start; live TS streams are what most of our inputs are.
            args += listOf("-analyzeduration", "3000000", "-probesize", "3000000")
        } else if (req.startMs > 0) {
            args += listOf("-ss", "%.3f".format(req.startMs / 1000.0))
        }
        if (req.readRealtime) args += "-re"
        args += listOf("-i", req.url)
        // First video + first audio; subtitles/data (teletext, DVB) would break HLS.
        args += listOf("-map", "0:v:0?", "-map", "0:a:0?", "-sn", "-dn")
        args += listOf("-max_muxing_queue_size", "1024")

        when (mode) {
            Mode.REMUX -> args += listOf("-c", "copy")
            Mode.TRANSCODE_HW, Mode.TRANSCODE_SW -> {
                // A forced transcode (debug) re-encodes everything; the normal
                // path copies whichever track the receiver can already play.
                val forced = req.forceMode != null
                val videoOk = !forced && (info.videoCodec == null || info.videoCodec in req.videoCodecsOk)
                val audioOk = !forced && (info.audioCodec == null || info.audioCodec in req.audioCodecsOk)
                if (videoOk) {
                    args += listOf("-c:v", "copy")
                } else if (mode == Mode.TRANSCODE_HW) {
                    args += listOf(
                        "-vf", "yadif=0:-1:1,format=nv12",
                        "-c:v", "h264_mediacodec", "-b:v", "5M", "-maxrate", "6M", "-bufsize", "10M",
                        "-g", "50",
                    )
                } else {
                    args += listOf(
                        "-vf", "yadif=0:-1:1,scale='min(1280,iw)':-2,format=yuv420p",
                        "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency",
                        "-crf", "23", "-maxrate", "4M", "-bufsize", "8M",
                        "-profile:v", "high", "-level:v", "4.1", "-g", "50",
                    )
                }
                if (audioOk) {
                    args += listOf("-c:a", "copy")
                } else {
                    args += listOf("-c:a", "aac", "-b:a", "160k", "-ac", "2", "-ar", "48000")
                }
            }
        }

        args += listOf(
            "-f", "hls",
            "-hls_time", SEGMENT_SECONDS.toString(),
            "-hls_list_size", (if (req.isLive) LIVE_LIST_SIZE else VOD_LIST_SIZE).toString(),
            "-hls_flags", "delete_segments+independent_segments+temp_file",
            "-hls_segment_type", "mpegts",
            "-hls_segment_filename", File(workDir, "seg%05d.ts").path,
            playlistFile.path,
        )
        return args
    }

    private fun clearWorkDir() {
        workDir.listFiles()?.forEach { it.delete() }
    }

    private fun appendLog(line: String) {
        val l = line.trimEnd()
        if (l.isBlank()) return
        synchronized(logTail) {
            if (logTail.size >= LOG_TAIL) logTail.removeFirst()
            logTail.addLast(l)
        }
    }

    private fun lastError(): String {
        val tail = logTail()
        val err = tail.lastOrNull { l ->
            val s = l.lowercase()
            s.contains("error") || s.contains("failed") || s.contains("invalid") ||
                s.contains("server returned") || s.contains("not found") || s.contains("refused")
        }
        return err?.trim() ?: "Stream ended unexpectedly"
    }

    init {
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_INFO)
    }
}
