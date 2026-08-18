package com.intempt.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Generates a Baseline Profile for :sample's cold start with the SDK initialized
 * (SampleApp.onCreate() already calls Intempt.initialize()). This is not an assertion of
 * application logic — BaselineProfileRule drives a real cold start on a real device/emulator
 * and records which classes/methods get touched, then writes a Human Readable Rules .txt.
 *
 * That output is not consumed automatically by :app (a library, not the app under test); see
 * app/src/main/baselineProfiles/README for how the generated rules are carried over.
 */
class BaselineProfileGenerator {
    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    private val packageName = "com.intempt.sample"

    @Before
    fun grantNotificationPermission() {
        // MainActivity requests POST_NOTIFICATIONS at runtime on Android 13+. Left ungranted,
        // the system permission dialog takes the foreground instead of MainActivity,
        // MainActivity never renders a frame, and amStartAndWait's frame-confirmation check
        // times out with "Unable to confirm activity launch completion" — the run fails before
        // any profile is collected. Granting it up front (before the app is even installed is
        // fine; the grant is requested against the package name, not a live process) keeps the
        // cold start on MainActivity, which is the path this profile is meant to cover.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
    }

    @Test
    fun generate() =
        baselineProfileRule.collect(
            packageName = packageName,
            includeInStartupProfile = true,
        ) {
            pressHome()
            startActivityAndWait()

            // Give the SDK's async init (Dagger graph build, config parse, queue bring-up) and
            // MainActivity's own view inflation time to settle before the iteration ends, so the
            // profile captures the full cold-start path rather than a truncated prefix of it.
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)
            Thread.sleep(2_000)
        }
}
