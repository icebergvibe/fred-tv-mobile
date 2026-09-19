package dev.fredol.open_tv.cast

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

/**
 * Flutter side of casting: a MethodChannel for commands and an EventChannel
 * that multiplexes route / session / media / error events as maps with a
 * "type" key. Mirrors lib/cast/cast_bridge.dart.
 */
class CastBridge(
    private val context: Context,
    messenger: BinaryMessenger,
) : MethodChannel.MethodCallHandler, EventChannel.StreamHandler, CastManager.Listener {

    companion object {
        const val METHOD_CHANNEL = "dev.fredol.open_tv/cast"
        const val EVENT_CHANNEL = "dev.fredol.open_tv/cast_events"
        private const val NOTIFICATION_PERMISSION_REQUEST = 4712
    }

    private val methodChannel = MethodChannel(messenger, METHOD_CHANNEL)
    private val eventChannel = EventChannel(messenger, EVENT_CHANNEL)
    private var sink: EventChannel.EventSink? = null

    init {
        methodChannel.setMethodCallHandler(this)
        eventChannel.setStreamHandler(this)
        CastManager.listener = this
    }

    fun dispose() {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        if (CastManager.listener === this) CastManager.listener = null
        sink = null
    }

    // ------------------------------------------------------ MethodChannel

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "init" -> result.success(CastManager.init(context))
            "isAvailable" -> result.success(CastManager.isAvailable())
            "getRoutes" -> result.success(CastManager.routes())
            "startDiscovery" -> {
                CastManager.startDiscovery(active = call.argument<Boolean>("active") ?: false)
                result.success(null)
            }
            "stopActiveDiscovery" -> {
                CastManager.stopActiveDiscovery()
                result.success(null)
            }
            "connect" -> {
                val id = call.argument<String>("routeId")
                result.success(id != null && CastManager.connect(id))
            }
            "disconnect" -> {
                CastManager.disconnect()
                result.success(null)
            }
            "getSessionState" -> result.success(CastManager.sessionState())
            "getMediaStatus" -> result.success(CastManager.mediaStatus())
            "castChannel" -> {
                val url = call.argument<String>("url")
                if (url.isNullOrEmpty()) {
                    result.error("bad_args", "url is required", null)
                    return
                }
                requestNotificationPermission()
                CastManager.castStream(
                    CastManager.StreamRequest(
                        url = url,
                        title = call.argument<String>("title") ?: "",
                        subtitle = call.argument<String>("subtitle"),
                        imageUrl = call.argument<String>("image"),
                        isLive = call.argument<Boolean>("isLive") ?: false,
                        startPositionMs = (call.argument<Number>("startPositionMs") ?: 0L).toLong(),
                        userAgent = call.argument<String>("userAgent"),
                        referer = call.argument<String>("referer"),
                        origin = call.argument<String>("origin"),
                    ),
                )
                result.success(null)
            }
            "getPipelineStatus" -> result.success(CastManager.pipelineStatus())
            "getPipelineLog" -> result.success(CastManager.pipelineLog())
            "load" -> {
                val url = call.argument<String>("url")
                if (url.isNullOrEmpty()) {
                    result.error("bad_args", "url is required", null)
                    return
                }
                CastManager.load(
                    url = url,
                    title = call.argument<String>("title") ?: "",
                    subtitle = call.argument<String>("subtitle"),
                    imageUrl = call.argument<String>("image"),
                    isLive = call.argument<Boolean>("isLive") ?: false,
                    contentType = call.argument<String>("contentType") ?: "video/mp4",
                    startPositionMs = (call.argument<Number>("startPositionMs") ?: 0L).toLong(),
                )
                result.success(null)
            }
            "play" -> { CastManager.play(); result.success(null) }
            "pause" -> { CastManager.pause(); result.success(null) }
            "stop" -> { CastManager.stop(); result.success(null) }
            "seekTo" -> {
                CastManager.seekTo((call.argument<Number>("positionMs") ?: 0L).toLong())
                result.success(null)
            }
            "setVolume" -> {
                CastManager.setVolume((call.argument<Number>("volume") ?: 1.0).toDouble())
                result.success(null)
            }
            "setMuted" -> {
                CastManager.setMuted(call.argument<Boolean>("muted") ?: false)
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    /** Android 13+: the foreground service notification is invisible without it. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val activity = context as? Activity ?: return
        val granted = activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            activity.requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST,
            )
        }
    }

    // ------------------------------------------------------- EventChannel

    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
        sink = events
        // Give the new listener the current picture immediately.
        onRoutesChanged(CastManager.routes())
        onSessionChanged(CastManager.sessionState())
        onMediaStatus(CastManager.mediaStatus())
        onPipelineChanged(CastManager.pipelineStatus())
    }

    override fun onCancel(arguments: Any?) {
        sink = null
    }

    private fun emit(type: String, payload: Map<String, Any?>) {
        val s = sink ?: return
        CastManager.post { s.success(payload + ("type" to type)) }
    }

    override fun onRoutesChanged(routes: List<Map<String, Any?>>) = emit("routes", mapOf("routes" to routes))
    override fun onSessionChanged(session: Map<String, Any?>) = emit("session", session)
    override fun onMediaStatus(status: Map<String, Any?>) = emit("media", status)
    override fun onPipelineChanged(pipeline: Map<String, Any?>) = emit("pipeline", pipeline)
    override fun onError(message: String) = emit("error", mapOf("message" to message))
}
