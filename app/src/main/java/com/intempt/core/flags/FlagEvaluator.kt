package com.intempt.core.flags

import com.intempt.core.types.FLAG_KEY_PATTERN
import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagDetail
import com.intempt.core.types.FlagError
import com.intempt.core.types.FlagErrorCode
import com.intempt.core.types.FlagErrorKind
import com.intempt.core.types.FlagErrorReporter
import com.intempt.core.types.FlagMap
import com.intempt.core.types.FlagReason
import com.intempt.core.types.isValidFlagKey
import com.intempt.core.types.transportErrorOf
import com.intempt.core.types.unwrapFlagValue
import java.util.concurrent.ConcurrentHashMap

private const val HTTP_NOT_FOUND = 404

/**
 * Failures the service may recover from. Not a 400/404 (the request or the flag is wrong, and a
 * deleted flag must stop serving) and not a 401/403 (a misconfigured key is not an outage).
 */
private val FALLBACK_KINDS =
    setOf(FlagErrorKind.TIMEOUT, FlagErrorKind.NETWORK, FlagErrorKind.SERVER, FlagErrorKind.PARSE)

/**
 * The flag evaluator (intempt-node `src/flag-evaluator.ts`, ffc4df6, is the spec). A flag is read
 * only over the `/flags` routes, never `/optimization/choose-api` (D20), and no call here throws (S11).
 *
 * Everything Android is injected — the HTTP call, the SSE opener, the timer and the coroutine
 * launcher — so this class compiles in `:mutation` and is tested on the plain JVM.
 */
@Suppress("LongParameterList") // each collaborator is injected so this class runs on the plain JVM
internal class FlagEvaluator(
    /** Never throws: a failure is a [FlagHttpResponse] with status 0. */
    private val post: suspend (route: String, body: Map<String, Any>) -> FlagHttpResponse,
    private val identity: (FlagContext) -> FlagIdentity,
    openStream: (StreamHandlers) -> StreamHandle,
    schedule: (Long, () -> Unit) -> Cancellable,
    private val launch: (suspend () -> Unit) -> Unit,
    val reporter: FlagErrorReporter,
    warn: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = FlagCache(clock)
    private val warnings = WarnOnce(warn)

    @Volatile
    private var connectedAt = 0L

    @Volatile
    private var closed = false

    private val stream =
        FlagStream(
            open = openStream,
            schedule = schedule,
            onChange = { keys -> cache.drop(keys) },
            onState = { connected ->
                if (connected) connectedAt = clock()
                cache.markPendingStale()
            },
        )

    internal val pendingReads: Int get() = cache.pendingCount

    /** Closes the stream until the next read; the app went to the background. */
    fun pause() = stream.stop()

    /** Ends the stream for good; reads still ask the server, exposures are no longer sent. */
    fun close() {
        closed = true
        stream.close()
    }

    /** Drops every cached answer: the person on this device changed. */
    fun forget() = cache.clear()

    suspend fun detail(
        key: String,
        context: FlagContext,
        defaultValue: Any?,
        disableExposure: Boolean = false,
    ): FlagDetail {
        val who = identity(context)
        return refusal(key, who, defaultValue) ?: lookup(key, who, defaultValue, disableExposure)
    }

    /** S11: a caller mistake returns the default, never throws; one warning per key keeps it visible. */
    private fun refusal(
        key: String,
        who: FlagIdentity,
        defaultValue: Any?,
    ): FlagDetail? =
        when {
            !isValidFlagKey(key) -> {
                warnings.warn(
                    "key:$key",
                    "flag key \"$key\" is empty or does not match ${FLAG_KEY_PATTERN.pattern} " +
                        "(the platform's own pattern); returning the default",
                )
                FlagDetail(defaultValue, FlagReason.ERROR, errorCode = FlagErrorCode.FLAG_NOT_FOUND)
            }
            !answerable(who, key) ->
                FlagDetail(defaultValue, FlagReason.ERROR, errorCode = FlagErrorCode.TARGETING_KEY_MISSING)
            else -> null
        }

    private suspend fun lookup(
        key: String,
        who: FlagIdentity,
        defaultValue: Any?,
        disableExposure: Boolean,
    ): FlagDetail {
        stream.start()
        val id = who.cacheKey(key)
        val fresh = cache.fresh(id, stream.connected, connectedAt)
        return fresh?.let { typedDetail(it, defaultValue) } ?: fetch(id, key, who, defaultValue, disableExposure)
    }

    private suspend fun fetch(
        id: String,
        key: String,
        who: FlagIdentity,
        defaultValue: Any?,
        disableExposure: Boolean,
    ): FlagDetail {
        val read = cache.begin(key)
        val started = clock()
        val trusted = stream.connected
        val outcome =
            try {
                if (disableExposure) unexposed(who, key) else evaluate(who, key)
            } finally {
                cache.end(read)
            }
        val error = outcome.error
        val flag = outcome.flag
        return when {
            error != null -> failed(id, error, defaultValue)
            flag == null -> {
                // Only a disableExposure read gets here: `/flags/all` leaves an unknown key out of
                // its answer where `/flags/evaluate` would answer 404.
                cache.forget(id)
                FlagDetail(defaultValue, FlagReason.ERROR, errorCode = FlagErrorCode.FLAG_NOT_FOUND)
            }
            else -> {
                // Off, killed or not targeted is forgotten, so an outage cannot serve the old value again.
                if (flag.hasValue) {
                    cache.remember(id, key, flag, started, trusted && !read.stale)
                } else {
                    cache.forget(id)
                }
                typedDetail(flag, defaultValue)
            }
        }
    }

    private fun failed(
        id: String,
        error: FlagError,
        defaultValue: Any?,
    ): FlagDetail {
        val cached = if (error.kind in FALLBACK_KINDS) cache.recall(id) else null
        if (cached != null) return typedDetail(cached, defaultValue, FlagReason.CACHED)
        val code = if (error.status == HTTP_NOT_FOUND) FlagErrorCode.FLAG_NOT_FOUND else FlagErrorCode.GENERAL
        // A deleted flag must not come back from the cache during a later outage.
        if (code == FlagErrorCode.FLAG_NOT_FOUND) cache.forget(id)
        return FlagDetail(defaultValue, FlagReason.ERROR, error, code)
    }

    /**
     * Every flag served to this person. `/flags/all` publishes nothing (F32 hybrid exposure), so the
     * map reports the exposure itself: the first read of each served key sends `/flags/expose` once
     * for this map. See [FlagMap] for which reads count.
     */
    suspend fun all(context: FlagContext): FlagMap {
        val who = identity(context)
        if (!answerable(who, null)) return emptyFlagMap()

        val outcome = allOutcomeOf(null, post(FlagRoute.ALL, allBody(who, null)))
        outcome.error?.let(reporter::report)
        return answerMap(who, outcome.flags)
    }

    private fun answerMap(
        who: FlagIdentity,
        flags: List<RawFlag>,
    ): FlagMap {
        val values = LinkedHashMap<String, Any?>()
        val served = ConcurrentHashMap<String, Long>()
        for (flag in flags) {
            val key = flag.key?.takeIf { it.isNotEmpty() } ?: continue
            val value = flag.value
            values[key] = if (value == null || !flag.hasValue) null else unwrapFlagValue(value)
            // Nothing served, or no variation named: there is no exposure the server could record,
            // and /flags/expose requires a variationId.
            val variationId = flag.variationId
            if (flag.hasValue && variationId != null) served[key] = variationId
        }
        // Always a FlagMap, even with nothing to expose: a bridge reaches peek/snapshot through the type.
        return ExposingFlagMap(values) { key -> served.remove(key)?.let { expose(who, key, it) } }
    }

    /**
     * Fire-and-forget: the read has already been answered, so a lost exposure costs one data point
     * and must never cost the caller a value, a throw or a wait. Not an `onFlagError`: no read failed.
     */
    private fun expose(
        who: FlagIdentity,
        key: String,
        variationId: Long,
    ) {
        if (closed) return
        launch {
            val response = post(FlagRoute.EXPOSE, exposeBody(who, key, variationId))
            transportErrorOf(key, response.status, response.timedOut)?.let { error ->
                val what = listOfNotNull(error.kind.wireName, error.status?.toString()).joinToString(" ")
                warnings.warn(
                    "expose:${error.kind.wireName}",
                    "flag exposure for \"$key\" was not recorded ($what); further ${error.kind.wireName} " +
                        "failures are not logged",
                )
            }
        }
    }

    /**
     * The flag routes take choose-api's `Identification` and answer 400 unless it names a visitor.
     * Checked here so the caller is told why, once per key, instead of a 400 per call.
     */
    private fun answerable(
        who: FlagIdentity,
        key: String?,
    ): Boolean {
        if (who.isAnswerable()) return true
        warnings.warn(
            "identity:$key",
            "flag \"${key ?: "(allFlags)"}\": context needs either userId, or profileId together with " +
                "a sourceId configured on the client; returning the default",
        )
        return false
    }

    private suspend fun evaluate(
        who: FlagIdentity,
        key: String,
    ): EvaluateOutcome =
        evaluateOutcomeOf(key, post(FlagRoute.EVALUATE, evaluateBody(who, key))).also { outcome ->
            outcome.error?.let(reporter::report)
        }

    /** `/flags/all` with one key: evaluates without publishing, for `disableExposure`. */
    private suspend fun unexposed(
        who: FlagIdentity,
        key: String,
    ): EvaluateOutcome {
        val outcome = allOutcomeOf(key, post(FlagRoute.ALL, allBody(who, listOf(key))))
        outcome.error?.let { error ->
            reporter.report(error)
            return EvaluateOutcome(null, error)
        }
        return EvaluateOutcome(outcome.flags.firstOrNull { it.key == key }, null)
    }
}

/** [FlagMap] over [backing]; [onRead] records a key's exposure and owns the once-per-map dedup. */
internal class ExposingFlagMap(
    private val backing: Map<String, Any?>,
    private val onRead: (String) -> Unit,
) : FlagMap,
    Map<String, Any?> by backing {
    override fun get(key: String): Any? {
        onRead(key)
        return backing[key]
    }

    // Not backing.getOrDefault: Map.getOrDefault is API 24 and minSdk is 23.
    override fun getOrDefault(
        key: String,
        defaultValue: Any?,
    ): Any? {
        onRead(key)
        return if (backing.containsKey(key)) backing[key] else defaultValue
    }

    override val values: Collection<Any?>
        get() {
            backing.keys.forEach(onRead)
            return backing.values
        }

    override val entries: Set<Map.Entry<String, Any?>>
        get() {
            backing.keys.forEach(onRead)
            return backing.entries
        }

    override fun peek(key: String): Any? = backing[key]

    override fun snapshot(): Map<String, Any?> = LinkedHashMap(backing)

    override fun expose(key: String) = onRead(key)

    override fun equals(other: Any?): Boolean = backing == other

    override fun hashCode(): Int = backing.hashCode()

    override fun toString(): String = backing.toString()
}

internal fun emptyFlagMap(): FlagMap = ExposingFlagMap(emptyMap()) {}
