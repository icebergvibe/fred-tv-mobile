package dev.fredol.open_tv.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/**
 * Referenced from AndroidManifest.xml (OPTIONS_PROVIDER_CLASS_NAME).
 * Uses Google's Default Media Receiver, so no Cast Developer Console
 * registration is needed.
 */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            // Reattach to a receiver we left running (e.g. after the app was killed)
            // so the user can still stop it from the app.
            .setResumeSavedSession(true)
            .setEnableReconnectionService(false)
            // Leaving a session must stop the receiver: the stream must never
            // keep running with nobody controlling it.
            .setStopReceiverApplicationWhenEndingSession(true)
            // CastService shows its own notification (with "Stop casting");
            // the SDK's media notification would just duplicate it.
            .setCastMediaOptions(
                CastMediaOptions.Builder().setNotificationOptions(null).build()
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
