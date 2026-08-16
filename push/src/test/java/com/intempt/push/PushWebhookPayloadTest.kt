package com.intempt.push

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.intempt.push.model.PushNotificationContent
import com.intempt.push.model.PushNotificationMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The push payload layer: what the SDK parses out of an FCM message, and what it reports back.
 *
 * Push had three tests before this, all of them structural — the manifest merges, the permission is
 * declared, the token reaches an event. Nothing covered the part that actually carries meaning:
 * eight identifiers copied from a server-supplied blob into a webhook body.
 *
 * That mapping is the highest-risk code in push and the least visible when wrong. Transposing
 * `orgId` and `projectId` compiles, runs, sends a webhook, gets a 200, and attributes every
 * delivery to the wrong project forever. No test here would have failed.
 */
class PushWebhookPayloadTest {
    private val mapper = jacksonObjectMapper()

    private fun metadata() =
        PushNotificationMetadata(
            orgId = "11",
            projectId = "22",
            transformerId = "33",
            pipelineId = "44",
            destinationId = "55",
            masterId = "66",
            accountId = "77",
            templateId = "88",
        )

    /**
     * Every identifier lands in its own field.
     *
     * Distinct values on purpose — eight fields all set to "1" would pass a transposed mapping,
     * which is the whole failure this guards.
     */
    @Test
    fun `each metadata identifier maps to its own webhook field`() {
        val request =
            PushNotificationWebhookRequest(
                PushNotificationWebhookRequest.WebhookType.DELIVERED,
                metadata(),
            )

        assertEquals(11L, request.orgId)
        assertEquals(22L, request.projectId)
        assertEquals(33L, request.transformerId)
        assertEquals(44L, request.pipelineId)
        assertEquals(55L, request.destinationId)
        assertEquals(66L, request.masterId)
        assertEquals(77L, request.accountId)
        assertEquals(88L, request.templateId)
    }

    /** The destination the platform routes on. Changing either silently misroutes every webhook. */
    @Test
    fun `subject and destination type identify FCM`() {
        val request =
            PushNotificationWebhookRequest(
                PushNotificationWebhookRequest.WebhookType.OPENED,
                metadata(),
            )

        assertEquals("firebase_cloud_messaging", request.subject)
        assertEquals("firebase_cloud_messaging", request.destinationType)
    }

    /**
     * `status` is derived from the type, lowercased, and must match the wire value.
     *
     * Two representations of one fact — `WebhookType.receivedType` and the lowercased enum name —
     * which is exactly the pair that drifts when someone renames a constant.
     */
    @Test
    fun `status matches the wire value for every webhook type`() {
        PushNotificationWebhookRequest.WebhookType.entries.forEach { type ->
            val request = PushNotificationWebhookRequest(type, metadata())

            assertEquals(
                "status must equal the type's declared wire value",
                type.receivedType,
                request.status,
            )
            assertEquals(type, request.type)
        }
    }

    @Test
    fun `the webhook types are the three the platform accepts`() {
        val wireValues = PushNotificationWebhookRequest.WebhookType.entries.map { it.receivedType }.toSet()

        assertEquals(setOf("bounced", "delivered", "opened"), wireValues)
    }

    /**
     * A non-numeric identifier throws rather than silently becoming 0.
     *
     * Pinned deliberately. The eight `toLong()` calls are on server-supplied strings, and the caller
     * in `FirebaseService` wraps them in a catch — so this throw is the mechanism by which a
     * malformed metadata blob costs one webhook instead of corrupting the attribution of every
     * later one. Coercing to 0 would report every delivery against org 0.
     */
    @Test
    fun `a non-numeric identifier is refused rather than coerced`() {
        val bad = metadata().copy(orgId = "not-a-number")

        assertThrows(NumberFormatException::class.java) {
            PushNotificationWebhookRequest(PushNotificationWebhookRequest.WebhookType.DELIVERED, bad)
        }

        assertThrows(NumberFormatException::class.java) {
            PushNotificationWebhookRequest(
                PushNotificationWebhookRequest.WebhookType.DELIVERED,
                metadata().copy(templateId = ""),
            )
        }
    }

    // -------------------------------------------------------------- content parsing

    @Test
    fun `content parses the fields a notification renders from`() {
        val json =
            """
            {"title":"Sale","body":"50% off","image":"https://x/i.png","webUrl":"https://x/sale"}
            """.trimIndent()

        val content = mapper.readValue<PushNotificationContent>(json)

        assertEquals("Sale", content.title)
        assertEquals("50% off", content.body)
        assertEquals("https://x/i.png", content.image)
        assertEquals("https://x/sale", content.webUrl)
    }

    /**
     * A field the SDK does not know about must not fail the parse.
     *
     * The server owns this payload and will add to it. Without `@JsonIgnoreProperties`, the first
     * new field ships a notification that renders as nothing on every device running an older SDK —
     * and it would look like a delivery problem, not a parsing one.
     */
    @Test
    fun `an unknown field does not fail the parse`() {
        val json = """{"title":"Sale","body":"b","somethingNew":"added next quarter"}"""

        val content = mapper.readValue<PushNotificationContent>(json)

        assertEquals("Sale", content.title)
        assertEquals("b", content.body)
    }

    /** Every field is optional, so a partial payload renders what it has rather than throwing. */
    @Test
    fun `absent fields parse as null`() {
        val content = mapper.readValue<PushNotificationContent>("""{"title":"Only a title"}""")

        assertEquals("Only a title", content.title)
        assertNull(content.body)
        assertNull(content.image)
        assertNull(content.webUrl)

        val empty = mapper.readValue<PushNotificationContent>("{}")
        assertNull(empty.title)
    }

    /**
     * Metadata parses from the JSON string FCM delivers it as.
     *
     * It arrives as a *string* inside `remoteMessage.data["metadata"]`, not as a nested object, so
     * this is the shape the SDK actually reads.
     */
    @Test
    fun `metadata parses from the data payload`() {
        val json =
            """
            {"orgId":"11","projectId":"22","transformerId":"33","pipelineId":"44",
             "destinationId":"55","masterId":"66","accountId":"77","templateId":"88"}
            """.trimIndent()

        val parsed = mapper.readValue<PushNotificationMetadata>(json)

        assertEquals(metadata(), parsed)

        // And it survives the round trip into the webhook body it exists to produce.
        val request = PushNotificationWebhookRequest(PushNotificationWebhookRequest.WebhookType.OPENED, parsed)
        assertEquals(11L, request.orgId)
        assertEquals(88L, request.templateId)
    }

    /**
     * The webhook body serializes with the field names the platform expects.
     *
     * Asserted on the JSON rather than the object, because the object being right and the wire
     * being wrong is the failure mode a getter-level test cannot see.
     */
    @Test
    fun `the serialized body carries every identifier by name`() {
        val json =
            mapper.writeValueAsString(
                PushNotificationWebhookRequest(PushNotificationWebhookRequest.WebhookType.DELIVERED, metadata()),
            )

        listOf(
            "\"orgId\":11",
            "\"projectId\":22",
            "\"transformerId\":33",
            "\"pipelineId\":44",
            "\"destinationId\":55",
            "\"masterId\":66",
            "\"accountId\":77",
            "\"templateId\":88",
            "\"status\":\"delivered\"",
            "\"destinationType\":\"firebase_cloud_messaging\"",
        ).forEach { assertTrue("missing $it in $json", json.contains(it)) }
    }
}
