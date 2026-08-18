package com.intempt.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.TraceSectionMetric
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
            metrics = metrics,
            compilationMode = compilationMode,
            startupMode = StartupMode.COLD,
            iterations = 5,
        ) {
            pressHome()
            startActivityAndWait()
        }

    private companion object {
        /**
         * [StartupTimingMetric] plus one [TraceSectionMetric] per section the SDK emits from
         * `Intempt.build()`.
         *
         * The startup metric is kept only for continuity with the historical number; it cannot
         * answer questions about this SDK. Real runs of the same code measured
         * `timeToInitialDisplayMs` at a median of 649.4ms on CI (min 615.1, max 652.1) and 417.4ms
         * locally, a 230ms environment difference against an SDK init of roughly 100ms — the noise
         * is larger than the whole signal. The trace sections are read straight out of the Perfetto
         * trace and contain only the SDK's own code, so they are comparable across environments.
         *
         * `Mode.Sum` because each section occurs once per startup; Sum then reports that one
         * duration and fails loudly (as 0) if the section never appeared, rather than silently
         * averaging over nothing.
         */
        @OptIn(ExperimentalMetricApi::class)
        val metrics: List<Metric> =
            listOf(
                StartupTimingMetric(),
                // Nothing has ever measured what the SDK costs in memory: it holds a 256-slot
                // event buffer, a SQLite queue and a HandlerThread. Mode.Last samples at the end
                // of the iteration (after init has settled) rather than the peak, so it reports
                // steady-state footprint; the default sub-metrics are HeapSize/RssAnon/RssFile.
                MemoryUsageMetric(MemoryUsageMetric.Mode.Last),
                TraceSectionMetric("Intempt.initialize", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.daggerGraph", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.config", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.initService", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.autocapture", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.pushBridge", mode = TraceSectionMetric.Mode.Sum),
                // Inside initService: one section per expensive Dagger provider. initService was
                // 21.84ms of a 27.81ms init and nothing said which constructor that was.
                TraceSectionMetric("Intempt.provideStorage", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.provideHttp", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.provideEventManager", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.provideQueueConfig", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.provideDelivery", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.provideEventPool", mode = TraceSectionMetric.Mode.Sum),
            )
    }
}
