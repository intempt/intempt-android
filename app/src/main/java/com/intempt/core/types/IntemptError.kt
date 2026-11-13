package com.intempt.core.types

/**
 * Why the SDK refused or failed to do something.
 *
 * Every capture method returns `Boolean` — accepted into the queue, or not. That answers *whether*
 * and never *why*, which is enough to notice a problem and not enough to fix one: a `false` from
 * `track()` is equally an opt-out, a reserved event name, a NaN in the attributes, and a full disk.
 * This is the *why*, delivered to [com.intempt.core.Intempt.setErrorListener].
 *
 * The case list is the cross-SDK contract's, so a React Native bridge can map one error taxonomy
 * to JavaScript rather than one per platform.
 *
 * ## Nothing here carries key material
 *
 * [MalformedApiKey] reports a **length**, not the key. An SDK that refuses a bad credential by
 * echoing it puts that credential wherever the error goes — a crash reporter, a support ticket, a
 * screenshot of logcat. Three of the five Intempt SDKs leaked their key through some printing path
 * before anyone looked, so this is a rule rather than a preference. See [IntemptCredentials], which
 * redacts for the same reason.
 *
 * ## Terminal versus retryable is a real distinction, not a severity label
 *
 * Delivery reports one [DeliveryFailure] per batch on its final outcome (CONTRACT-R2 S2,
 * INT-7444): the batch was kept because it never reached the server (`transport`), the server
 * answered a status that drops it (`terminal`, which includes 401 and 402 since INT-7438), or the
 * device dropped it (`dropped`). [Terminal], [Retryable] and [Transport] are the pre-INT-7444
 * per-attempt reports; delivery no longer emits them, and they stay so existing `when` branches
 * compile. `HttpStatusPolicy` decides; this is how a host app hears about the decision.
 */
sealed class IntemptError {
    /**
     * Plain classes, not data classes, and that is a size decision rather than a style one.
     *
     * `data` generates `copy`, `copy$default`, `componentN`, `equals`, `hashCode` and `toString`
     * per subclass. Across twelve cases that was 74 members in the declared surface, and the CI
     * method-count gate caught it: nobody copies an error with a different status, and nobody
     * destructures one. Consumers match with `is` and read the fields.
     *
     * [toString] is defined once on this class instead, so every case still prints usefully.
     *
     * [message] is a one-line description safe to log. It never contains a credential.
     */
    abstract val message: String

    /** The API key was not `<id>.<secret>`. Reports [length] only — never the key itself. */
    class MalformedApiKey(val length: Int) : IntemptError() {
        override val message: String
            get() =
                "The API key is not in \"<id>.<secret>\" form (length $length). " +
                    "No Authorization header can be built."
    }

    /**
     * A required credential was blank.
     *
     * [fieldName], not `field`: inside a property getter `field` is Kotlin's soft keyword for the
     * backing field, so `"$field"` here referred to `message`'s own backing field — which a
     * getter-only property does not have — and the compiler rejected the class with "Property must
     * be initialized". A parameter name that silently changes meaning inside an accessor is worth
     * not having.
     */
    class MissingConfiguration(val fieldName: String) : IntemptError() {
        override val message: String get() = "Missing configuration: $fieldName is blank."
    }

    /**
     * An attribute value cannot be transmitted — a NaN or an infinity, at [key] or nested inside it.
     *
     * Refused at the call site rather than at the gateway on purpose. Non-finite numbers are not
     * JSON, and the gateway rejects the **whole batch**, so one bad value would lose every event
     * queued alongside it.
     */
    class InvalidPropertyValue(val key: String) : IntemptError() {
        override val message: String get() = "Attribute \"$key\" is not representable (NaN or infinity)."
    }

    /** An identifier the event type requires was absent or blank. */
    class MissingIdentity(val what: String) : IntemptError() {
        override val message: String get() = "Missing identifier: $what."
    }

    /** The payload could not be serialized. */
    class EncodingFailed(val reason: String) : IntemptError() {
        override val message: String get() = "Could not encode the payload: $reason."
    }

    /** The server rejected the batch with a status that will never succeed on retry. */
    class Terminal(val status: Int) : IntemptError() {
        override val message: String get() = "Terminal HTTP $status; this batch will not be retried."
    }

    /** A transient failure. [retryAfterMillis] is the server's `Retry-After` when it sent one. */
    class Retryable(val status: Int, val retryAfterMillis: Long? = null) : IntemptError() {
        override val message: String
            get() =
                "Retryable HTTP $status" +
                    (retryAfterMillis?.let { "; retrying after ${it}ms" } ?: "; retrying with backoff") + "."
    }

    /** The network layer failed before a status was available. */
    class Transport(val description: String) : IntemptError() {
        override val message: String get() = "Transport failure: $description."
    }

    /**
     * The final outcome of one batch that did not deliver. [kind] is `transport`, `terminal` or
     * `dropped`; [status] is the HTTP status when the server answered; [retryAfter] is in seconds;
     * [terminal] is false only for `transport`, where the batch is still queued.
     */
    class DeliveryFailure(
        val kind: String,
        val status: Int?,
        val eventCount: Int,
        val eventIds: List<String>,
        val retryAfter: Int?,
        val terminal: Boolean,
    ) : IntemptError() {
        override val message: String
            get() =
                "Delivery failure ($kind): $eventCount event(s)" +
                    (status?.let { ", HTTP $it" } ?: "") +
                    (if (terminal) ", dropped." else ", kept for the next flush.")
    }

    /** The durable queue could not persist the event, so it is lost rather than delayed. */
    class StorageUnavailable(val reason: String) : IntemptError() {
        override val message: String get() = "Queue storage unavailable: $reason."
    }

    /** The server rejected the request and said why. */
    class Server(val status: Int, val messages: List<String>) : IntemptError() {
        override val message: String get() = "Server rejected with HTTP $status: ${messages.joinToString("; ")}."
    }

    /**
     * The call was refused because the user has opted out.
     *
     * Not in the contract's table, and deliberately added: without it every opted-out call reports
     * `false` with no case to match on, and an integrator debugging "why did nothing send" cannot
     * distinguish a working opt-out from a broken SDK. It is the single most common reason a
     * capture method returns false, so leaving it unnamed would make the listener useless in
     * exactly the case people reach for it.
     */
    class OptedOut(val caller: String) : IntemptError() {
        override val message: String get() = "$caller was refused: the user has opted out."
    }

    /** The event name is one the platform reserves for its own events. */
    class ForbiddenEventName(val eventTitle: String) : IntemptError() {
        override val message: String get() = "The event title \"$eventTitle\" is reserved by the platform."
    }

    override fun toString(): String = "${this::class.java.simpleName}: $message"
}
