@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.eventPool.EventPoolManagerService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * INT-7443: `products()` always asked the feed for the anonymous profile, so an identified user got
 * the anonymous recommendations. A caller-supplied userId (Beso, D2: not persisted from identify)
 * is sent as `{id: userId, type: "user"}`; without one the request is unchanged.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecommendationBodyUserIdTest {
    private val storage: StorageManagerService = mock(StorageManagerService::class.java)
    private val config: ConfigManagerService = mock(ConfigManagerService::class.java)
    private val service =
        IntemptEventManagerService(
            ApplicationProvider.getApplicationContext<Context>(),
            storage,
            UtilsService(mock(LoggerManagerService::class.java)),
            config,
        )

    init {
        `when`(storage.getProfileId()).thenReturn("prof-9")
        `when`(config.sourceId).thenReturn("src-9")
    }

    @Test
    fun `a userId asks for that user's recommendations`() {
        val body = service.generateRecommendationBody(3, listOf("name"), null, "u1")

        assertEquals("u1", body["id"])
        assertEquals("user", body["type"])
        assertEquals("src-9", body["sourceId"])
        assertEquals(3, body["limit"])
    }

    @Test
    fun `no userId, or a blank one, keeps the profile request`() {
        for (userId in listOf(null, "", " ")) {
            val body = service.generateRecommendationBody(3, listOf("name"), "p1", userId)

            assertEquals("prof-9", body["id"])
            assertEquals("profile", body["type"])
            assertEquals("p1", body["productId"])
        }
    }

    @Test
    fun `the feed request and the capture component pass the userId on`(): Unit =
        runBlocking {
            val events = mock(IntemptEventManagerService::class.java)
            `when`(events.generateRecommendationBody(3, listOf("name"), null, "u1")).thenReturn(emptyMap())
            `when`(config.recommendationUrl("7")).thenReturn("https://example.invalid/feeds/7/data")
            val pool =
                EventPoolManagerService(
                    config,
                    mock(LoggerManagerService::class.java),
                    mock(HttpManagerService::class.java),
                    events,
                    mock(DeliveryMessages::class.java),
                )

            pool.getFeedData("7", 3, listOf("name"), null, "u1")
            verify(events).generateRecommendationBody(3, listOf("name"), null, "u1")

            val mockedPool = mock(EventPoolManagerService::class.java)
            CustomCaptureComponent(
                mock(CustomCaptureService::class.java),
                config,
                mockedPool,
                events,
                UtilsService(mock(LoggerManagerService::class.java)),
                storage,
                mock(ErrorReporter::class.java),
            ).products("7", 3, listOf("name"), null, "u1")
            verify(mockedPool).getFeedData("7", 3, listOf("name"), null, "u1")
        }
}
