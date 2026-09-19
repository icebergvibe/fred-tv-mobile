package dev.fredol.open_tv.cast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug builds only. Lets the pipeline be driven from adb without a Cast
 * session, so remux/transcode can be verified on an emulator:
 *
 *   adb shell am broadcast -a dev.fredol.open_tv.CAST_DEBUG \
 *       --es url "http://..." --ez live true
 *   adb shell am broadcast -a dev.fredol.open_tv.CAST_DEBUG --es cmd stop
 *
 * The local playlist URL is printed to logcat under the CastDebug tag.
 */
class CastDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url")
        val cmd = intent.getStringExtra("cmd")
        // Creates <ext>/Android/data/<pkg>/files with the app as owner so
        // test inputs can be adb-pushed there.
        Log.i("CastDebug", "external files dir: ${context.getExternalFilesDir(null)}")
        CastManager.post {
            when {
                cmd == "stop" -> CastManager.debugStopPipeline()
                cmd == "log" -> CastManager.pipelineLog().forEach { Log.i("CastDebug", "  | $it") }
                url != null -> CastManager.debugStartPipeline(
                    context,
                    url,
                    intent.getBooleanExtra("live", true),
                    intent.getLongExtra("start", 0L),
                    when (intent.getStringExtra("mode")) {
                        "remux" -> StreamPipeline.Mode.REMUX
                        "hw" -> StreamPipeline.Mode.TRANSCODE_HW
                        "sw" -> StreamPipeline.Mode.TRANSCODE_SW
                        else -> null
                    },
                )
                else -> Log.w("CastDebug", "nothing to do")
            }
        }
    }
}
