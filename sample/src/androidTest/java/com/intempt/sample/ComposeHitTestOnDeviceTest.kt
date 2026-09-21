package com.intempt.sample

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 4.1.0 Compose hit-test, proven on a device: a real touch on a `Modifier.testTag` node inside
 * a Compose screen produces a `Touch event` whose `targetId` IS that tag.
 *
 * Before 4.1.0 this tap produced no touch event at all — `AndroidComposeView` is a ViewGroup with
 * no View children, so the tree walk returned null. The assertion below therefore fails on 4.0.2
 * by timeout, not by a wrong value.
 *
 * The touch is injected with `Instrumentation.sendPointerSync`, which enters the Activity through
 * `Window.Callback.dispatchTouchEvent` — the same path a finger takes and the hook the SDK wraps.
 * Espresso's `click()` would work too but drags in a synchronisation layer this proof does not
 * need. The result is read from the durable queue on disk, the same way `SdkOnDeviceTest` does.
 */
@RunWith(AndroidJUnit4::class)
class ComposeHitTestOnDeviceTest {
    private companion object {
        const val DB = "intempt_events"
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 500L
    }

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun rows(): List<JSONObject> {
        val file = context().getDatabasePath(DB)
        if (!file.exists()) return emptyList()
        val out = mutableListOf<JSONObject>()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT data FROM events ORDER BY _id", null).use { c ->
                while (c.moveToNext()) runCatching { out.add(JSONObject(c.getString(0))) }
            }
        }
        return out
    }

    /** `targetId` of every queued Touch event whose `targetId` equals [tag]. */
    private fun touchEventsTargeting(tag: String): List<JSONObject> =
        rows().filter { row ->
            row.optString("name") == "Touch event" &&
                row.optJSONArray("payload")?.optJSONObject(0)?.optJSONObject("data")?.optString("targetId") == tag
        }

    private fun tapCentreOf(bounds: android.graphics.Rect) {
        val inst = InstrumentationRegistry.getInstrumentation()
        val x = bounds.exactCenterX()
        val y = bounds.exactCenterY()
        val down = SystemClock.uptimeMillis()
        inst.sendPointerSync(MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0))
        inst.sendPointerSync(MotionEvent.obtain(down, down + 50, MotionEvent.ACTION_UP, x, y, 0))
    }

    @Test
    fun tapOnComposeTestTagSurfacesTheTagAsTargetId() {
        ActivityScenario.launch(ComposeDemoActivity::class.java).use { scenario ->
            var bounds: android.graphics.Rect? = null
            val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
            while (bounds == null && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { bounds = it.ctaBounds }
                if (bounds == null) SystemClock.sleep(POLL_MS)
            }
            assertNotNull("the tagged node was never laid out", bounds)

            // Re-tap while polling: the queue row is deleted as soon as delivery confirms it, so
            // a single tap can be queued, sent and gone inside one poll interval.
            var hits = emptyList<JSONObject>()
            while (hits.isEmpty() && SystemClock.uptimeMillis() < deadline) {
                tapCentreOf(bounds!!)
                SystemClock.sleep(POLL_MS)
                hits = touchEventsTargeting(ComposeDemoActivity.CTA_TAG)
            }

            assertEquals(
                "no Touch event carried targetId=${ComposeDemoActivity.CTA_TAG}; queued names: " +
                    rows().map { it.optString("name") }.distinct(),
                ComposeDemoActivity.CTA_TAG,
                hits.firstOrNull()?.optJSONArray("payload")?.optJSONObject(0)?.optJSONObject("data")?.optString("targetId"),
            )
        }
    }
}
