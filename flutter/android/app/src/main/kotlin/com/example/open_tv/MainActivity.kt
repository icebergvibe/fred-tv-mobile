package dev.fredol.open_tv

import android.view.KeyEvent
import dev.fredol.open_tv.cast.CastBridge
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var castBridge: CastBridge? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        flutterEngine.platformViewsController.registry.registerViewFactory(
            "dev.fredol.open_tv/exoplayer",
            ExoPlayerViewFactory(flutterEngine.dartExecutor.binaryMessenger),
        )
        castBridge = CastBridge(this, flutterEngine.dartExecutor.binaryMessenger)
    }

    override fun cleanUpFlutterEngine(flutterEngine: FlutterEngine) {
        castBridge?.dispose()
        castBridge = null
        super.cleanUpFlutterEngine(flutterEngine)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (ExoPlayerView.active?.dispatchDpad(event) == true) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
