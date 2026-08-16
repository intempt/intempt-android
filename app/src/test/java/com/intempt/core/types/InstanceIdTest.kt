package com.intempt.core.types

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Storage scoping, which is the whole of what named instances actually needed.
 *
 * Dagger already gave one object graph per `initialize()`. What it could not scope is disk: two
 * instances would open the same `SharedPreferences` files and the same SQLite queue, so the second
 * would read the first's `profileId` and post the first's events under its own credentials. That
 * looks like working software and is silent data corruption, which is why it is asserted here
 * rather than left to an integration test nobody runs.
 */
class InstanceIdTest {
    @Test
    fun `two instances never share a storage name`() {
        val a = InstanceId("tenant-a")
        val b = InstanceId("tenant-b")

        listOf("session_prefs", "user_prefs", "intempt_events", "intempt_consent_audit").forEach { base ->
            assertNotEquals(
                "instances must not share $base — the second would inherit the first's identity",
                a.scope(base),
                b.scope(base),
            )
        }
    }

    /**
     * The default instance's names are unchanged.
     *
     * A single-project app is every app today, and suffixing its storage would orphan whatever is
     * already on disk — including a queue of undelivered events, which is the one thing an upgrade
     * must not silently discard.
     */
    @Test
    fun `the default instance is not suffixed`() {
        assertEquals("session_prefs", InstanceId.Default.scope("session_prefs"))
        assertEquals("intempt_events", InstanceId("default").scope("intempt_events"))
    }

    @Test
    fun `a named instance is suffixed with its name`() {
        assertEquals("session_prefs_secondary", InstanceId("secondary").scope("session_prefs"))
        assertEquals("intempt_events_secondary", InstanceId("secondary").scope("intempt_events"))
    }

    /**
     * Scoping is a pure function of the name, so the same instance always reaches the same storage
     * across process restarts. Trivial to state, and the property a durable queue depends on: a
     * name that scoped differently on the second launch would strand every queued event.
     */
    @Test
    fun `scoping is stable for the same name`() {
        assertEquals(InstanceId("t1").scope("user_prefs"), InstanceId("t1").scope("user_prefs"))
    }
}
