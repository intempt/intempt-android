package com.intempt.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Measures whether the SDK's UI instrumentation costs frames.
 *
 * Nothing has ever measured this. Autocapture installs a `Window.Callback` wrapper that sees every
 * `MotionEvent` before the host app does, plus `ActivityLifecycleCallbacks` — both sit on the main
 * thread in the touch and draw path, which is exactly where a jank regression would appear and
 * exactly where no existing metric looks. `StartupTimingMetric` cannot see it: it stops at the
 * first frame.
 *
 * :sample's MainActivity is plain views, not Compose: one `ScrollView` holding ~14 `Button`s and
 * two `EditText`s. That is real, scrollable, tappable content, so the fling below produces genuine
 * frames rather than a synthetic animation — but it is a simple layout, so treat the absolute
 * numbers as a regression tripwire for the SDK's hooks, not as a device-accurate jank figure.
 *
 * WARM rather than COLD: this measures steady-state rendering, and a cold start would put process
 * creation and SDK init inside the frame window where StartupBenchmark already measures them.
 */
class FrameBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val packageName = "com.intempt.sample"

    @Before
    fun grantNotificationPermission() {
        // Same rationale as StartupBenchmark: an ungranted runtime permission prompt steals the
        // foreground from MainActivity and the frame-confirmation wait times out.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
    }

    @Test
    fun scrollAndTap() =
        benchmarkRule.measureRepeated(
            packageName = packageName,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = 5,
        ) {
            startActivityAndWait()

            // Wait for real content, not just the window: without this the first fling can land
            // before the buttons are laid out and measure an empty screen.
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)

            val content = device.findObject(By.scrollable(true)) ?: return@measureRepeated
            // setGestureMargin keeps the fling clear of the system gesture insets, which would
            // otherwise be consumed as a back/home gesture instead of scrolling the app.
            content.setGestureMargin(device.displayWidth / 5)
            repeat(2) {
                content.fling(Direction.DOWN)
                content.fling(Direction.UP)
            }

            // A tap as well as scrolls: autocapture's control-interaction hook runs on click
            // dispatch, and a scroll-only workload never exercises it.
            device.findObject(By.text("track"))?.click()
            device.waitForIdle()
        }
}
