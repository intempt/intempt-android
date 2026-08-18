package com.intempt.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Measures :sample's actual cold-start timing rather than only generating a profile.
 *
 * Two [CompilationMode]s are run so the Baseline Profile's real effect is visible in one report:
 * `None()` is a cold start with no AOT compilation at all (worst case, what a fresh install looks
 * like before ART has compiled anything), and `Partial(BaselineProfileMode.Require)` forces the
 * device to install and honor app/src/main/baselineProfiles/baseline-prof.txt (fails loudly if
 * that profile is missing or malformed, rather than silently falling back to none).
 *
 * This exists because a prior hand-measurement (adb `am start -W`, 5 runs, median) found real but
 * highly variable deltas (~100-340ms) across separate emulator-boot sessions, and nothing tracked
 * the number over time. This is intentionally informational (see the CI job's
 * `continue-on-error: true`) until a handful of real CI runs establish actual environment
 * variance — see baselineprofile/cold-start-baseline.json.
 */
class StartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    private val packageName = "com.intempt.sample"

    @Before
    fun grantNotificationPermission() {
        // Same rationale as BaselineProfileGenerator: an ungranted runtime permission prompt
        // steals the foreground from MainActivity and the frame-confirmation wait times out.
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            .executeShellCommand("pm grant $packageName android.permission.POST_NOTIFICATIONS")
    }

    @Test
    fun startupCompilationNone() = measureStartup(CompilationMode.None())

    @Test
    fun startupCompilationBaselineProfile() =
        measureStartup(CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require))

    private fun measureStartup(compilationMode: CompilationMode) =
        benchmarkRule.measureRepeated(
            packageName = packageName,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
        ) {
            pressHome()
            startActivityAndWait()
        }
}
