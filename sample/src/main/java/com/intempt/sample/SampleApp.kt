package com.intempt.sample

import android.app.Application
import com.intempt.core.Intempt

/**
 * Initialization happens in Application.onCreate, which is where a host app should do it:
 * autocapture registers activity and fragment lifecycle callbacks, so anything initialized
 * from the first Activity has already missed that Activity's own creation.
 */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Intempt.initialize(this)
    }
}
