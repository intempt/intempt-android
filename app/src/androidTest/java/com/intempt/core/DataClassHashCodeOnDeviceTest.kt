package com.intempt.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.intempt.core.eventModels.ScreenViewEvent
import com.intempt.core.eventModels.SessionEvent
import com.intempt.core.eventModels.SessionUserAttributes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the toolchain assumption that three AnimalSniffer exclusions rest on.
 *
 * AnimalSniffer reports `java.lang.Long.hashCode(long)`, `Boolean.hashCode(boolean)` and
 * `Integer.hashCode(int)` as unavailable below API 24, and it is right that they are: they are the
 * static Java 8 overloads. They appear in this SDK only inside the `hashCode()` that Kotlin
 * generates for a data class holding a primitive — no hand-written code calls them.
 *
 * The reason those are excluded rather than fixed is that D8 backports them unconditionally, so
 * they are rewritten before they reach a device. That is a claim about the build tools, and the
 * exclusions are worthless if it is wrong: every `hashCode()` on an event model would throw
 * NoSuchMethodError on Android 6, and only on Android 6.
 *
 * A comment asserting "D8 handles it" would not be evidence. This runs on the API 23 emulator in
 * CI and calls the generated `hashCode()` for real. If the backport ever stops, this fails on the
 * device instead of the exclusion quietly hiding it.
 *
 * Deliberately in `app/src/androidTest` rather than `sample`: the event models are `internal`, so
 * they are only visible from within this module.
 */
@RunWith(AndroidJUnit4::class)
class DataClassHashCodeOnDeviceTest {
    /** A data class whose primitive field is a Long — the `Long.hashCode(long)` case. */
    @Test
    fun hashCodeOnADataClassWithALongFieldWorksAtThisApiLevel() {
        val event =
            SessionEvent(
                eventId = "evt-1",
                sessionId = "sess-1",
                pageId = "page-1",
                profileId = "prof-1",
                timestamp = 1_700_000_000_000L,
                sessionStartEventName = "session_start",
                deviceName = "device",
                appName = "sample",
                appVersion = "1.0.0",
                appIdentifier = "com.intempt.sample",
                androidId = "aid",
                userAttributes =
                    SessionUserAttributes(deviceType = "phone", carrier = "", platform = "android"),
            )

        // The call itself is the assertion: on an API 23 without D8's backport this throws
        // NoSuchMethodError. There is deliberately no assertNotNull on the result — hashCode()
        // returns an Int, which cannot be null, so such an assertion could never fail. Writing one
        // here would be the same empty-assertion mistake this whole test exists to guard against.
        val hash = event.hashCode()

        assertEquals(
            "hashCode must be stable across calls at API ${android.os.Build.VERSION.SDK_INT}",
            hash,
            event.hashCode(),
        )
    }

    /** A nullable Long, which generates a slightly different hashCode path. */
    @Test
    fun hashCodeOnADataClassWithANullableLongWorksAtThisApiLevel() {
        fun screenView(timeOnScreen: Long?) =
            ScreenViewEvent(
                eventId = "evt-1",
                sessionId = "sess-1",
                pageId = "page-1",
                profileId = "prof-1",
                timestamp = 1_700_000_000_000L,
                activity = "MainActivity",
                fullActivity = "com.example.MainActivity",
                screenName = "Home",
                timeOnScreen = timeOnScreen,
            )

        // Both calls are the assertion; the null branch generates its own bytecode path. Compared
        // for equality rather than non-nullity, since an Int is never null.
        assertEquals(screenView(4_200L).hashCode(), screenView(4_200L).hashCode())
        assertEquals(screenView(null).hashCode(), screenView(null).hashCode())
        assertNotEquals(
            "a different timeOnScreen must produce a different hash, or the field is not in the " +
                "generated hashCode at all and this test would pass without exercising it",
            screenView(4_200L).hashCode(),
            screenView(null).hashCode(),
        )
    }

    /** equals() shares the generated primitive comparisons, so it is exercised too. */
    @Test
    fun equalsOnADataClassWithPrimitiveFieldsWorksAtThisApiLevel() {
        fun screenView() =
            ScreenViewEvent(
                eventId = "evt-1",
                sessionId = "sess-1",
                pageId = "page-1",
                profileId = "prof-1",
                timestamp = 1_700_000_000_000L,
                activity = "MainActivity",
                fullActivity = "com.example.MainActivity",
                screenName = "Home",
                timeOnScreen = 10L,
            )

        assertEquals(screenView(), screenView())
    }

    /**
     * The models are also put into HashMaps and HashSets by the event pool, which calls the
     * generated hashCode on every insert. This is the path a real event actually takes.
     */
    @Test
    fun eventModelsCanBeUsedAsMapKeysAtThisApiLevel() {
        val event =
            ScreenViewEvent(
                eventId = "evt-1",
                sessionId = "sess-1",
                pageId = "page-1",
                profileId = "prof-1",
                timestamp = 1_700_000_000_000L,
                activity = "MainActivity",
                fullActivity = "com.example.MainActivity",
                screenName = "Home",
                timeOnScreen = 10L,
            )

        val seen = HashMap<ScreenViewEvent, String>()
        seen[event] = "first"

        assertEquals("first", seen[event])
        assertEquals("an equal event must resolve to the same bucket", 1, seen.size)
    }
}
