package com.intempt.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.Metric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.TraceSectionMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.regex.Pattern

/**
 * Measures what one `Intempt.track()` call costs.
 *
 * This is the number that matters most and the one nothing measured: init runs once per process,
 * `track()` runs thousands of times per session, so a millisecond here is worth far more than a
 * millisecond in `initialize()`.
 *
 * Three sections are read:
 * - `Intempt.track` — the whole public call, from the facade through validation, payload build and
 *   the handoff to the durable queue. This is the cost a host app actually pays on its own thread.
 * - `Intempt.trackPayload` — property collection and JSON construction.
 * - `Intempt.trackEnqueue` — the `MutableSharedFlow.tryEmit` handoff. Shared by every capture call
 *   and by autocapture, so its Count can exceed the track count; read the counts, not just the
 *   sums.
 *
 * Two more sections come from autocapture rather than from `track()`, and are measured here because
 * this workload already produces 20 real `ACTION_UP`s per iteration:
 * - `Intempt.touchDispatch` — autocapture's whole `Window.Callback` hook, on the main thread inside
 *   the host app's touch dispatch.
 * - `Intempt.findTouchedView` — the recursive view-tree walk inside it, nested in the above. This
 *   is the only part that scales with the host's hierarchy, and it runs before the 320 ms debounce,
 *   so it is paid on every touch-up rather than once per burst.
 *
 * Caveat on those two: `:sample`'s layout is one `ScrollView` over ~16 direct children. A real app
 * with a deep Compose or nested-RecyclerView tree walks far more nodes, so read the number as a
 * floor and a regression tripwire, not as what a production host pays.
 *
 * Each section is reported as Sum and Average. Mode.Sum already emits a `...Count` measurement of
 * its own, so an explicit Mode.Count metric is not added — the two collide on the same output name
 * and macrobenchmark fails the run ("Multiple metrics produced measurements with overlapping
 * names"). Average is the per-call cost; Sum and the Sum-emitted Count are kept so `Sum / Count`
 * can be checked against Average rather than trusted.
 *
 * WARM, not COLD: this measures the steady-state capture path, and a cold start would fold process
 * creation and SDK init into the same iteration where StartupBenchmark already measures them.
 */
class TrackBenchmark {
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
    fun trackRepeatedly() =
        benchmarkRule.measureRepeated(
            packageName = packageName,
            metrics = metrics,
            compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
            startupMode = StartupMode.WARM,
            iterations = 5,
        ) {
            startActivityAndWait()

            // Wait for real content, not just the window: without this the first tap can land
            // before the buttons are laid out and measure nothing at all.
            device.wait(Until.hasObject(By.pkg(packageName).depth(0)), 5_000)

            // Case-INSENSITIVE. A plain By.text("track") matches nothing: Button applies
            // textAllCaps, so the node's text is "TRACK". FrameBenchmark carried that selector
            // behind a `?.click()` and therefore never tapped anything for the whole life of the
            // benchmark while still reporting frames.
            //
            // Not null-safe here, deliberately: a benchmark that cannot find its workload must
            // fail, not report a track cost of zero.
            val button =
                requireNotNull(device.findObject(By.text(TRACK_BUTTON))) {
                    "No track button in :sample — the workload never ran, so any measurement would be a lie"
                }
            repeat(TAPS_PER_ITERATION) {
                button.click()
            }
            device.waitForIdle()
        }

    private companion object {
        /**
         * Enough taps that one call's cost is not lost in tap dispatch jitter, few enough that the
         * iteration stays inside the trace buffer. `Intempt.trackCount` in the report is the truth
         * about how many actually landed — read it rather than assuming this number.
         */
        const val TAPS_PER_ITERATION = 20

        /** `Button` uppercases its label, so this must not be an exact-case match. */
        val TRACK_BUTTON: Pattern = Pattern.compile("track", Pattern.CASE_INSENSITIVE)

        @OptIn(ExperimentalMetricApi::class)
        val metrics: List<Metric> =
            listOf(
                TraceSectionMetric("Intempt.track", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.track", mode = TraceSectionMetric.Mode.Average),
                TraceSectionMetric("Intempt.trackPayload", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.trackPayload", mode = TraceSectionMetric.Mode.Average),
                TraceSectionMetric("Intempt.trackEnqueue", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.trackEnqueue", mode = TraceSectionMetric.Mode.Average),
                TraceSectionMetric("Intempt.touchDispatch", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.touchDispatch", mode = TraceSectionMetric.Mode.Average),
                TraceSectionMetric("Intempt.findTouchedView", mode = TraceSectionMetric.Mode.Sum),
                TraceSectionMetric("Intempt.findTouchedView", mode = TraceSectionMetric.Mode.Average),
            )
    }
}
