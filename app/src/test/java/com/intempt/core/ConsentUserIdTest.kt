@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.eventModels.ConsentEvent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.ConsentAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * INT-7442: the consent backend keys a decision on profileId, userId or masterId, and the SDK sent
 * only the anonymous profileId, so a known user's decision was not attached to them. The SDK does
 * not persist the identified user (Beso, D2), so the caller passes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConsentUserIdTest {
    private fun event(userId: String?) =
        ConsentEvent(
            eventId = "e1",
            sessionId = "s1",
            pageId = "p1",
            profileId = "prof-1",
            action = "accept",
            sourceId = "src-1",
            validUntil = 9L,
            userId = userId,
        ).toFormated()

    @Test
    fun `a consent with a userId sends it beside the profileId`() {
        val body = event("u1")

        assertEquals("u1", body["userId"])
        assertEquals("prof-1", body["profileId"])
        assertEquals("src-1", body["sourceId"])
    }

    @Test
    fun `a consent without a userId, or with a blank one, sends no userId key`() {
        assertFalse(event(null).containsKey("userId"))
        assertFalse(event("").containsKey("userId"))
        assertFalse(event("  ").containsKey("userId"))
    }

    @Test
    fun `the payload builder carries the userId into the event`() {
        val storage = mock(StorageManagerService::class.java)
        `when`(storage.getProfileId()).thenReturn("prof-9")
        `when`(storage.getSessionId()).thenReturn("sess-9")
        `when`(storage.getPageId()).thenReturn("page-9")
        val service =
            IntemptEventManagerService(
                ApplicationProvider.getApplicationContext<Context>(),
                storage,
                UtilsService(mock(LoggerManagerService::class.java)),
                mock(ConfigManagerService::class.java),
            )

        val body =
            service
                .generateConsentEventPayload("accept", validUntil = 9L, sourceId = "src-9", userId = "u1")
                .single()
                .toFormated()

        assertEquals("u1", body["userId"])
    }

    @Test
    fun `the capture component hands the caller's userId to the payload builder`() {
        val config = mock(ConfigManagerService::class.java)
        `when`(config.sourceId).thenReturn("src-1")
        val events = mock(IntemptEventManagerService::class.java)
        val srv = mock(CustomCaptureService::class.java)
        `when`(srv.logger).thenReturn(mock(LoggerManagerService::class.java))
        val component =
            CustomCaptureComponent(
                srv,
                config,
                mock(EventPoolManagerService::class.java),
                events,
                UtilsService(mock(LoggerManagerService::class.java)),
                mock(StorageManagerService::class.java),
                mock(ErrorReporter::class.java),
            )

        component.consent(ConsentAction.ACCEPT, 9L, category = "marketing", userId = "u1")

        verify(events).generateConsentEventPayload(
            any(),
            anyOrNull(),
            anyOrNull(),
            eq("marketing"),
            eq(9L),
            eq("src-1"),
            eq("u1"),
        )
    }
}
