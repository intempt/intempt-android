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
 * [Terminal] means the request will never succeed on retry; [Retryable] means it should be retried
 * with backoff. Queued events are **kept** on a terminal 401, because the data is valid and the
 * integration is what is broken — deleting them is total silent data loss, and it has already
 * happened once in this transport. `HttpStatusPolicy` is the code that decides; this is how a host
 * app hears about the decision.
 */
sealed class IntemptError {
    /** A one-line description safe to log. Never contains a credential. */
    abstract val message: String

    /** The API key was not `<id>.<secret>`. Reports [length] only — never the key itself. */
    data class MalformedApiKey(val length: Int) : IntemptError() {
        override val message: String
            get() = "The API key is not in \"<id>.<secret>\" form (length $length). No Authorization header can be built."
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
    data class MissingConfiguration(val fieldName: String) : IntemptError() {
        override val message: String get() = "Missing configuration: $fieldName is blank."
    }

    /**
     * An attribute value cannot be transmitted — a NaN or an infinity, at [key] or nested inside it.
     *
     * Refused at the call site rather than at the gateway on purpose. Non-finite numbers are not
     * JSON, and the gateway rejects the **whole batch**, so one bad value would lose every event
     * queued alongside it.
     */
    data class InvalidPropertyValue(val key: String) : IntemptError() {
        override val message: String get() = "Attribute \"$key\" is not representable (NaN or infinity)."
    }

    /** An identifier the event type requires was absent or blank. */
    data class MissingIdentity(val what: String) : IntemptError() {
        override val message: String get() = "Missing identifier: $what."
    }

    /** The payload could not be serialized. */
    data class EncodingFailed(val reason: String) : IntemptError() {
        override val message: String get() = "Could not encode the payload: $reason."
    }

    /** The server rejected the batch with a status that will never succeed on retry. */
    data class Terminal(val status: Int) : IntemptError() {
        override val message: String get() = "Terminal HTTP $status; this batch will not be retried."
    }

    /** A transient failure. [retryAfterMillis] is the server's `Retry-After` when it sent one. */
    data class Retryable(val status: Int, val retryAfterMillis: Long? = null) : IntemptError() {
        override val message: String
            get() =
                "Retryable HTTP $status" +
                    (retryAfterMillis?.let { "; retrying after ${it}ms" } ?: "; retrying with backoff") + "."
    }

    /** The network layer failed before a status was available. */
    data class Transport(val description: String) : IntemptError() {
        override val message: String get() = "Transport failure: $description."
    }

    /** The durable queue could not persist the event, so it is lost rather than delayed. */
    data class StorageUnavailable(val reason: String) : IntemptError() {
        override val message: String get() = "Queue storage unavailable: $reason."
    }

    /** The server rejected the request and said why. */
    data class Server(val status: Int, val messages: List<String>) : IntemptError() {
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
    data class OptedOut(val caller: String) : IntemptError() {
        override val message: String get() = "$caller was refused: the user has opted out."
    }

    /** The event name is one the platform reserves for its own events. */
    data class ForbiddenEventName(val eventTitle: String) : IntemptError() {
        override val message: String get() = "The event title \"$eventTitle\" is reserved by the platform."
    }

    override fun toString(): String = "${this::class.java.simpleName}: $message"
}
