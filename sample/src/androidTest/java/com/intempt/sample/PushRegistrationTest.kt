package com.intempt.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Push, from the host app's side.
 *
 * The SDK contributes `FirebaseService`, `NotificationDispatcherActivity` and the
 * `POST_NOTIFICATIONS` permission through its own manifest, so a consuming app declares none of
 * them. What the app must supply is a Firebase configuration — and that is the half that has never
 * been verified, because the manifest merge is the seam where a consumer's setup and the SDK's
 * assumptions meet.
 *
 * These tests skip rather than fail when there is no `sample/google-services.json`. A skip is
 * honest about not having run; failing a build for a credential that legitimately is not in the
 * repository trains people to ignore the job.
 *
 * ## What this cannot cover
 *
 * Delivery. A real end-to-end push needs the FCM service account uploaded to the Intempt console
 * and a journey configured to send — neither of which lives in this repository, and neither of
 * which a device test can stand in for. This verifies everything up to that boundary: the
 * components merged, the token registered, and the SDK reporting itself ready to receive.
 */
@RunWith(AndroidJUnit4::class)
class PushRegistrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The SDK's push components must survive manifest merge into a consumer.
     *
     * This is the one assertion that works without Firebase, because it asks about the merged
     * manifest rather than about a live connection. If the SDK ever moved these out of its own
     * manifest, every consumer would silently stop receiving push with nothing failing at build
     * time.
     */
    @Test
    fun theSdkPushComponentsAreMergedIntoTheHostApp() {
        val pm = context.packageManager
        val services =
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SERVICES)
                .services.orEmpty().map { it.name }
        val activities =
            pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_ACTIVITIES)
                .activities.orEmpty().map { it.name }

        assertTrue(
            "the SDK's FirebaseService did not merge into the host manifest, so no push can ever " +
                "be received. Services present: $services",
            services.any { it.contains("FirebaseService") },
        )
        assertTrue(
            "NotificationDispatcherActivity did not merge, so tapping a notification would do " +
                "nothing. Activities present: $activities",
            activities.any { it.contains("NotificationDispatcherActivity") },
        )
    }

    /** The permission the SDK contributes, without which Android 13+ shows nothing. */
    @Test
    fun thePostNotificationsPermissionIsDeclared() {
        val requested =
            context.packageManager
                .getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().toList()

        assertTrue(
            "POST_NOTIFICATIONS is contributed by the SDK's manifest and must reach the host app, " +
                "or Android 13+ will never display a notification. Requested: $requested",
            requested.any { it == android.Manifest.permission.POST_NOTIFICATIONS },
        )
    }

    // A test calling FirebaseMessaging.getInstance() directly used to sit here. It does not
    // compile, and that is correct rather than a problem: the SDK depends on firebase-messaging
    // as `implementation`, so the type is absent from a consumer's compile classpath. A host app
    // never needs it — the SDK owns push end to end.
    //
    // Adding it as a test-only dependency would have worked, and would also have blurred the line
    // this project exists to keep sharp: what a consumer can and cannot see. The payload check
    // below is the assertion that matters anyway, and it needs no Firebase types.

    /**
     * The token has to reach the payload, not just the device.
     *
     * Read from the SDK's own queue rather than from Firebase, because that is what actually
     * leaves for the platform. Skipped without Firebase, since there would be no token to send.
     */
    @Test
    fun theTokenReachesTheEventPayload() {
        assumeTrue("push not configured in this build", BuildConfig.PUSH_ENABLED)
        assumeTrue("the SDK must be initialized", com.intempt.core.Intempt.isInitialized)

        val deadline = System.currentTimeMillis() + 30_000
        var found = false
        while (System.currentTimeMillis() < deadline && !found) {
            found = queuedRows().any { row -> row.toString().contains("fcm_token_") }
            if (!found) Thread.sleep(250)
        }

        assertTrue(
            "no `fcm_token_<sourceId>` in any queued event after 30s. Either Firebase issued no " +
                "token — google-services.json present but the project misconfigured, or an " +
                "emulator image without Google Play Services (API 23 `default` will not do it, " +
                "`google_apis` is required) — or it issued one and the SDK never attached it. " +
                "Either way the device is unaddressable by any push journey and nothing else " +
                "about the SDK looks wrong.",
            found,
        )
    }

    /** The SDK's queue, read directly — the same source the delivery tests use. */
    private fun queuedRows(): List<org.json.JSONObject> {
        val file = context.getDatabasePath("intempt_events")
        if (!file.exists()) return emptyList()
        val out = mutableListOf<org.json.JSONObject>()
        runCatching {
            android.database.sqlite.SQLiteDatabase
                .openDatabase(file.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY)
                .use { db ->
                    db.rawQuery("SELECT data FROM events ORDER BY _id", null).use { c ->
                        while (c.moveToNext()) runCatching { out.add(org.json.JSONObject(c.getString(0))) }
                    }
                }
        }
        return out
    }
}
