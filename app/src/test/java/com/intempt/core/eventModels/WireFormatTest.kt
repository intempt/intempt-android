package com.intempt.core.eventModels

import com.intempt.core.types.AppVisibilityState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `toFormated()` is the wire contract with api.intempt.com. Every one of these models measured
 * 0.0% line coverage, which means the shape the platform actually receives was never asserted
 * anywhere in the SDK.
 *
 * That is a contract, not an implementation detail. Renaming a key, dropping a level of nesting,
 * or moving a field between `data` and `userAttributes` produces an SDK that still compiles, still
 * passes every other test, and still returns 200 from the platform — while the field silently
 * stops landing on the profile. There is no failure signal anywhere in that chain, which is why
 * the assertions here are on exact keys and exact nesting rather than on round-tripping.
 *
 * The envelope is identical across every event type: sessionId, eventId, pageId, profileId and
 * timestamp at the top level, with the event's own fields under `data`. That invariant is asserted
 * per model rather than once over a loop, so a break names the model it broke.
 */
class WireFormatTest {
    private companion object {
        const val EVENT_ID = "evt-1"
        const val SESSION_ID = "sess-1"
        const val PAGE_ID = "page-1"
        const val PROFILE_ID = "prof-1"
        const val TIMESTAMP = 1_700_000_000_000L
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.data(): Map<String, Any?> = this["data"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.userAttributes(): Map<String, Any?> = this["userAttributes"] as Map<String, Any?>

    /** The five envelope fields the platform routes on. Wrong here means the event is unattributable. */
    private fun assertEnvelope(formatted: Map<String, Any>) {
        assertEquals(SESSION_ID, formatted["sessionId"])
        assertEquals(EVENT_ID, formatted["eventId"])
        assertEquals(PAGE_ID, formatted["pageId"])
        assertEquals(PROFILE_ID, formatted["profileId"])
        assertEquals(TIMESTAMP, formatted["timestamp"])
    }

    // ------------------------------------------------------------ screen view

    @Test
    fun `screen view carries its four fields under data`() {
        val formatted =
            ScreenViewEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                activity = "MainActivity",
                fullActivity = "com.example.MainActivity",
                screenName = "Home",
                timeOnScreen = 4_200L,
            ).toFormated()

        assertEnvelope(formatted)
        val data = formatted.data()
        assertEquals("MainActivity", data["activity"])
        assertEquals("com.example.MainActivity", data["fullActivity"])
        assertEquals("Home", data["screenName"])
        assertEquals(4_200L, data["timeOnScreen"])
        assertEquals("screen view has no userAttributes block", 6, formatted.size)
    }

    /**
     * `timeOnScreen` is nullable — it is absent on the first view of a screen. The key is still
     * emitted with a null value rather than dropped, so this pins which of the two the platform
     * receives. Changing it is a contract change either way.
     */
    @Test
    fun `an absent timeOnScreen is emitted as a null key rather than dropped`() {
        val data =
            ScreenViewEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                activity = "MainActivity",
                fullActivity = "com.example.MainActivity",
                screenName = "Home",
                timeOnScreen = null,
            ).toFormated().data()

        assertTrue("the key must still be present", data.containsKey("timeOnScreen"))
        assertNull(data["timeOnScreen"])
    }

    @Test
    fun `screen view reports its timestamp as the event time`() {
        val event =
            ScreenViewEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                activity = "A",
                fullActivity = "a.A",
                screenName = "S",
                timeOnScreen = null,
            )

        assertEquals(
            "ordering in the queue depends on getEventTime matching the emitted timestamp",
            TIMESTAMP,
            event.getEventTime(),
        )
        // getEventTime() and toFormated()["timestamp"] both return the same field with no
        // transform, so comparing them to each other proves nothing. Both are compared to the
        // constant instead.
        assertEquals(TIMESTAMP, event.toFormated()["timestamp"])
    }

    // --------------------------------------------------------------- session

    @Test
    fun `session splits device facts under data and profile facts under userAttributes`() {
        val formatted =
            SessionEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                sessionStartEventName = "session_start",
                deviceName = "Pixel 7",
                appName = "Sample",
                appVersion = "1.2.3",
                appIdentifier = "com.example.sample",
                androidId = "aid-123",
                userAttributes =
                    SessionUserAttributes(
                        deviceType = "phone",
                        carrier = "Cosmote",
                        platform = "android",
                    ),
            ).toFormated()

        assertEnvelope(formatted)

        val data = formatted.data()
        assertEquals("session_start", data["sessionStartEventName"])
        assertEquals("Pixel 7", data["deviceName"])
        assertEquals("Sample", data["appName"])
        assertEquals("1.2.3", data["appVersion"])
        assertEquals("com.example.sample", data["appIdentifier"])
        assertEquals("aid-123", data["androidId"])
        assertEquals("the platform keys off this to pick the Android pipeline", "android", data["source"])

        val attrs = formatted.userAttributes()
        assertEquals("phone", attrs["deviceType"])
        assertEquals("Cosmote", attrs["carrier"])
        assertEquals("android", attrs["platform"])

        // Geo is server-derived now, so none of these may appear. Asserted as absence rather than
        // just dropped from the test: a field silently reappearing on the wire is exactly the kind
        // of regression that would restore the third-party IP flow without anyone noticing.
        listOf("ipAddress", "city", "region", "country").forEach {
            assertTrue(
                "`$it` must not be on the wire — the device no longer handles its own IP, and the " +
                    "platform derives geo from the request via the ?ip= parameter",
                !attrs.containsKey(it),
            )
        }
        assertEquals("only device facts remain in userAttributes", 3, attrs.size)
    }

    /**
     * `source` is defaulted rather than passed in. If that default ever changed, events would be
     * attributed to the wrong platform with nothing failing, so it is pinned separately from the
     * case above where it could be read as incidental.
     */
    @Test
    fun `session source defaults to android`() {
        val event =
            SessionEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                sessionStartEventName = "session_start",
                deviceName = "Pixel 7",
                appName = "Sample",
                appVersion = "1.2.3",
                appIdentifier = "com.example.sample",
                androidId = "aid-123",
                userAttributes =
                    SessionUserAttributes(deviceType = "phone", carrier = "", platform = "android"),
            )

        assertEquals("android", event.source)
    }

    /**
     * SessionUserAttributes carries only what the device can know.
     *
     * <p>It previously had ipAddress/city/region/country, all populated from a per-session call to
     * ipapi.co. Pinned as a shape assertion so re-adding a geo field is a deliberate act with a
     * failing test attached, not a quiet edit.
     */
    @Test
    fun `session user attributes carry no geo fields`() {
        val fields = SessionUserAttributes::class.java.declaredFields.map { it.name }.toSet()

        listOf("ipAddress", "city", "region", "country").forEach {
            assertTrue("SessionUserAttributes must not carry `$it`", !fields.contains(it))
        }
        assertTrue("deviceType is still device-known", fields.contains("deviceType"))
    }

    // ----------------------------------------------------- fragment transition

    @Test
    fun `fragment transition carries all three fragment names`() {
        val formatted =
            FragmentTransitionEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                visibleFragment = "CartFragment",
                addedFragment = "CheckoutFragment",
                removedFragment = "BrowseFragment",
            ).toFormated()

        assertEnvelope(formatted)
        val data = formatted.data()
        assertEquals("CartFragment", data["visibleFragment"])
        assertEquals("CheckoutFragment", data["addedFragment"])
        assertEquals("BrowseFragment", data["removedFragment"])
    }

    /**
     * A transition with no fragment added or removed still emits both keys as empty strings. The
     * tracker relies on that: it always supplies all three, so an omitted key would mean a bug
     * upstream rather than an absent fragment.
     */
    @Test
    fun `a transition with nothing added or removed still emits both keys`() {
        val data =
            FragmentTransitionEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                visibleFragment = "CartFragment",
                addedFragment = "",
                removedFragment = "",
            ).toFormated().data()

        assertTrue(data.containsKey("addedFragment"))
        assertTrue(data.containsKey("removedFragment"))
        assertEquals("", data["addedFragment"])
        assertEquals("", data["removedFragment"])
    }

    // ------------------------------------------------------------- toString

    /**
     * `toString` is what lands in logcat, so it must not be the data-class default — that would
     * dump every constructor argument, and on `InstallOrUpgradeEvent` one of those is the FCM
     * token. A credential in logcat is readable by any app with log access on older Androids.
     */
    @Test
    fun `event toString is a shaped summary rather than the data-class default`() {
        val rendered =
            ScreenViewEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                activity = "MainActivity",
                fullActivity = "com.example.MainActivity",
                screenName = "Home",
                timeOnScreen = 10L,
            ).toString()

        assertTrue("the override must be in use", rendered.startsWith("{"))
        assertTrue("a shaped summary must not read as the generated one", !rendered.startsWith("ScreenViewEvent("))

        // Every field is named. The previous version checked only `startsWith("{")` plus one
        // field, which a toString that had dropped everything else would still have satisfied —
        // and the point of a shaped summary is that it stays useful in logcat.
        listOf(
            "sessionId: $SESSION_ID",
            "eventId: $EVENT_ID",
            "pageId: $PAGE_ID",
            "profileId: $PROFILE_ID",
            "timestamp: $TIMESTAMP",
            "activity: MainActivity",
            "screenName: Home",
        ).forEach { assertTrue("the rendering dropped `$it`: $rendered", rendered.contains(it)) }
    }

    /** The nullable field is omitted from the rendering rather than printed as the word "null". */
    @Test
    fun `toString omits an absent timeOnScreen instead of printing null`() {
        val rendered =
            ScreenViewEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                activity = "MainActivity",
                fullActivity = "com.example.MainActivity",
                screenName = "Home",
                timeOnScreen = null,
            ).toString()

        assertTrue("an absent duration must not render as the literal null", !rendered.contains("timeOnScreen"))
    }

    @Test
    fun `fragment transition toString names all three fragments`() {
        val rendered =
            FragmentTransitionEvent(
                eventId = EVENT_ID,
                sessionId = SESSION_ID,
                pageId = PAGE_ID,
                profileId = PROFILE_ID,
                timestamp = TIMESTAMP,
                visibleFragment = "CartFragment",
                addedFragment = "CheckoutFragment",
                removedFragment = "BrowseFragment",
            ).toString()

        assertTrue(rendered.contains("visibleFragment: CartFragment"))
        assertTrue(rendered.contains("addedFragment: CheckoutFragment"))
        assertTrue(rendered.contains("removedFragment: BrowseFragment"))
    }

    // --------------------------------------------------------------- equality

    // A test asserting that two ScreenViewEvents with different ids are unequal used to live here.
    // It was removed rather than kept: ScreenViewEvent declares no equals, so the test exercised
    // the Kotlin compiler's generated data-class equality and would have failed only if someone
    // deleted the `data` keyword. Nothing in the SDK depends on that equality today.

    // ------------------------------------------------------------- visibility

    /**
     * `InstallOrUpgradeEvent` puts the `AppVisibilityState` object straight into the payload
     * rather than its `key`, so what reaches the platform is whatever `toString()` renders. For a
     * `data object` that happens to be the declaration name, which currently equals `key` — but
     * only by coincidence, and nothing enforces it.
     *
     * That coincidence is load-bearing: renaming either the object or its key silently changes the
     * value on the wire, and the platform matches on it. Pinned here so the break is a test
     * failure rather than a field quietly going unmatched in production.
     */
    @Test
    fun `visibility states serialise to the same value as their key`() {
        assertEquals("Foreground", AppVisibilityState.Foreground.key)
        assertEquals("Background", AppVisibilityState.Background.key)

        assertEquals(
            "the payload embeds the object, not the key, so toString must agree with it",
            AppVisibilityState.Foreground.key,
            AppVisibilityState.Foreground.toString(),
        )
        assertEquals(
            AppVisibilityState.Background.key,
            AppVisibilityState.Background.toString(),
        )
    }
}
