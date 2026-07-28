package com.woolab.lumella

import android.app.Application
import com.ffalcon.mercury.android.sdk.MercurySDK

/**
 * Application entry point for the RayNeo native-glasses build. Registered as
 * `android:name` in the manifest (required so the launcher classifies LUMELLA as a
 * native app rather than a "virtual machine"/touchpad-relay app — see
 * `com.rayneo.mercury.app` meta-data on the `<application>` tag).
 *
 * [MercurySDK.init] MUST run before any [BaseMirrorActivity]-derived activity starts
 * (ported from LEGACY `TUTOR/ELLA`'s `EllaApp`).
 */
class LumellaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        MercurySDK.init(this)
    }
}
