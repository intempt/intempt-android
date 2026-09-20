@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services

import android.content.Context
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.types.FlagContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The two Android-coupled seams 4.1.0 added, which `:mutation` cannot reach (see docs/TESTING.md,
 * "Neither gate covers the Android-coupled half"):
 *
 * - `generateChooseBody` must hand the SDK's OWN session id to `buildChooseBody`. The pure
 *   function is under the mutation gate; that this caller passes `storage.getSessionId()` rather
 *   than null is only provable here.
 * - `generateUiElementEventPayload` must let a resolved Compose `testTag` replace `targetId`, and
 *   only `targetId` — and must ignore a blank one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class ChooseBodySessionAndTargetIdOverrideTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val storage: StorageManagerService = mock(StorageManagerService::class.java)
    private val config: ConfigManagerService = mock(ConfigManagerService::class.java)
    private val utils = UtilsService(mock(LoggerManagerService::class.java))
    private val service = IntemptEventManagerService(context, storage, utils, config)

    init {
        `when`(storage.getSessionId()).thenReturn("sess-9")
        `when`(storage.getProfileId()).thenReturn("prof-9")
        `when`(storage.getPageId()).thenReturn("page-9")
        `when`(config.sourceId).thenReturn("src-9")
    }

    @Test
    fun `the choose body carries the session id the storage holds`() {
        val body = service.generateChooseBody(FlagContext(), null)

        assertEquals("sess-9", body["sessionId"])
    }

    @Suppress("UNCHECKED_CAST")
    private fun data(override: String?): Map<String, Any> {
        val event = service.generateUiElementEventPayload(FrameLayout(context), override)!!.single()
        return event.toFormated()["data"] as Map<String, Any>
    }

    @Test
    fun `a resolved compose tag becomes targetId and nothing else changes`() {
        val d = data("compose_cta")

        assertEquals("compose_cta", d["targetId"])
        // The View has NO_ID, so every View-derived field is what it was before 4.1.0.
        assertEquals("unknown", d["fullTargetId"])
        assertEquals("FrameLayout", d["targetElement"])
    }

    @Test
    fun `no override and a blank override both leave the pre-4_1_0 value`() {
        assertEquals("unknown", data(null)["targetId"])
        assertEquals("unknown", data("   ")["targetId"])
        assertFalse(data(null).containsKey("targetIdOverride"))
    }
}
