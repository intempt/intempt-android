@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services.eventPool

import com.intempt.core.queue.ConsentAuditLog
import com.intempt.core.queue.DeliveryFailure
import com.intempt.core.queue.consentFailureKind
import com.intempt.core.queue.consentResendDelayMs
import com.intempt.core.queue.retryAfterSeconds
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.PostOutcome
import com.intempt.core.types.IntemptError
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Consent delivery, send-once like the event queue (R3, Beso 2026-11-13): a 503 or 429 is resent
 * once after its Retry-After (1-60 s), any other answer or a failure after the write drops the
 * consent, and one that never reached the server stays pending for the next drain. Every failure is
 * reported once through [errors] (R2) and logged at error level.
 *
 * Its own class, not more members on EventPoolManagerService, which is at detekt's function and
 * constructor limits.
 */
internal class ConsentSender(
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
    private val http: HttpManagerService,
    private val audit: ConsentAuditLog?,
    private val clock: () -> Long,
) {
    /**
     * Set through EventPoolManagerService.reportConsentFailuresTo by CustomCaptureComponent, which
     * holds both ends, rather than injected: the event pool's constructor and its provider are at
     * detekt's parameter limits. Until it is set (a drain started by the pool's own init can finish
     * first) a failure is still logged at error level.
     */
    @Volatile
    var errors: ErrorReporter? = null

    private val drainLock = Mutex()

    // The kept consent already reported as transport, so a device that stays offline reports it
    // once rather than on every drain (the event queue's mTransportReportedHead). In memory only.
    private var transportReportedId = -1L

    /**
     * Sends pending consents oldest first, one request at a time (INT-3904). A never-reached one
     * stops the drain, so a later decision never overtakes it. A consent confirmed with 2xx is
     * marked delivered and never sent again.
     */
    suspend fun drain() {
        val log = audit ?: return
        drainLock.withLock {
            var pending = log.oldestPending()
            while (pending != null && sendPending(log, pending)) {
                pending = log.oldestPending()
            }
        }
    }

    /** No usable local store: one send-once attempt. Nothing can keep it, so never-reached is dropped. */
    suspend fun sendUnstored(payload: JSONObject) {
        val outcome = sendOnce(payload)
        val kind = consentFailureKind(outcome.status, outcome.notSent) ?: return
        logger.error("sendConsentEvent | consent event was NOT delivered to ${config.consentUrl}")
        report(if (kind == DeliveryFailure.Kind.TRANSPORT) DeliveryFailure.Kind.DROPPED else kind, outcome)
    }

    /** Returns true when the drain may continue with the next record. */
    private suspend fun sendPending(
        log: ConsentAuditLog,
        pending: ConsentAuditLog.PendingConsent,
    ): Boolean {
        if (clock() - pending.createdAt > MAX_AGE_MS) {
            // A never-reached consent is the only kind left pending; this bounds it.
            log.markDropped(pending.id)
            logger.error("sendConsentEvent | consent ${pending.id} dropped: undelivered for over $MAX_AGE_DAYS days")
            report(DeliveryFailure.Kind.DROPPED, PostOutcome(0))
            return true
        }
        return apply(log, pending.id, sendOnce(pending.payload))
    }

    private suspend fun sendOnce(payload: JSONObject): PostOutcome {
        val first = http.postForStatus(config.consentUrl, payload)
        val waitMs = consentResendDelayMs(first.status, first.retryAfterMs)
        if (waitMs < 0L) return first
        delay(waitMs)
        return http.postForStatus(config.consentUrl, payload)
    }

    private fun apply(
        log: ConsentAuditLog,
        id: Long,
        outcome: PostOutcome,
    ): Boolean =
        when (val kind = consentFailureKind(outcome.status, outcome.notSent)) {
            null -> {
                log.markDelivered(id)
                true
            }
            DeliveryFailure.Kind.TRANSPORT -> {
                logger.error(
                    "sendConsentEvent | consent $id was NOT delivered to ${config.consentUrl}: it never " +
                        "reached the server. It stays pending for the next flush.",
                )
                if (transportReportedId != id) {
                    transportReportedId = id
                    report(kind, outcome)
                }
                false
            }
            else -> {
                log.markDropped(id)
                logger.error(
                    "sendConsentEvent | consent $id was NOT delivered to ${config.consentUrl} " +
                        "(status ${outcome.status}) and is dropped; it is not resent.",
                )
                report(kind, outcome)
                true
            }
        }

    /** eventIds is empty: the consent body carries no eventId on the wire (ConsentEvent.toFormated). */
    private fun report(
        kind: DeliveryFailure.Kind,
        outcome: PostOutcome,
    ) {
        errors?.report(
            IntemptError.DeliveryFailure(
                kind = kind.wireName,
                status = outcome.status.takeIf { it > 0 },
                eventCount = 1,
                eventIds = emptyList(),
                retryAfter = retryAfterSeconds(outcome.retryAfterMs).takeIf { it > 0 },
                terminal = kind != DeliveryFailure.Kind.TRANSPORT,
            ),
        )
    }

    private companion object {
        const val MAX_AGE_DAYS = 30L
        val MAX_AGE_MS = TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)
    }
}
