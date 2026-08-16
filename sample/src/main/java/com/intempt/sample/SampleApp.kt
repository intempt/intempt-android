package com.intempt.sample

import android.app.Application
import android.util.Log
import com.intempt.core.Intempt
import com.intempt.core.types.AutomaticEventsOptions

/**
 * Initialization happens in Application.onCreate, which is where a host app should do it:
 * autocapture registers activity and fragment lifecycle callbacks, so anything initialized from
 * the first Activity has already missed that Activity's own creation.
 *
 * This file is the shape a customer copies, so it shows the 3.0 surface rather than the shortest
 * thing that compiles: the return value is checked, errors are subscribed to, and the automatic
 * events the sample wants are named rather than assumed.
 */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // Checked, not ignored. initialize() returns Boolean precisely so a host app can tell a
        // working SDK from a dead one — it used to return Unit, and an app shipped without the
        // config asset was told nothing while every batch 401'd and was dropped.
        val started = Intempt.initialize(this)
        if (!started) {
            Log.e(TAG, "Intempt did not start. Check src/main/assets/intempt-config.json.")
            return
        }

        // The *why* behind every `false` a capture method returns. Without this the sample would
        // demonstrate the same blind spot the Boolean return exists to remove.
        Intempt.setErrorListener { error -> Log.w(TAG, "Intempt refused something — $error") }

        // Named rather than left to the defaults, so this file documents what the sample emits.
        // versionChanges and appStateChanges default to OFF: the SDK used to emit both
        // unconditionally, which is an event on every foreground/background transition that
        // nobody asked for.
        Intempt.automaticEvents =
            AutomaticEventsOptions(
                sessions = true,
                versionChanges = true,
                appStateChanges = false,
            )

        // Autocapture is started by initialize() only because assets/intempt-config.json sets
        // isAutoCaptureEnabled. Logged so the sample makes the opt-in visible rather than leaving
        // a reader to wonder why UI events appear without a start() call anywhere.
        Log.i(TAG, "Autocapture running: ${Intempt.autocapture.isRunning()}, options ${Intempt.autocapture.options()}")
        Log.i(TAG, "profileId=${Intempt.getProfileId()}")
    }

    private companion object {
        const val TAG = "IntemptSample"
    }
}
