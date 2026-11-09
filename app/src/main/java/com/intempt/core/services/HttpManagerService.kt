@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services

import com.intempt.core.internal.InternalIntemptApi
import com.intempt.core.internal.traced
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton

private const val HTTP_OK_FIRST = 200
private const val HTTP_OK_LAST = 299
private const val MILLIS_PER_SECOND = 1000L

/**
 * `Retry-After` as milliseconds from [nowMs] (RFC 9110 section 10.2.3): either a number of seconds
 * or an HTTP-date. Null when absent or unparseable; never negative (a date in the past is 0).
 */
@InternalIntemptApi
fun parseRetryAfterMs(
    value: String?,
    nowMs: Long,
): Long? {
    val text = value?.trim().orEmpty()
    return text.toLongOrNull()?.let { (it * MILLIS_PER_SECOND).coerceAtLeast(0L) }
        ?: parseHttpDate(text)?.let { (it - nowMs).coerceAtLeast(0L) }
}

private fun parseHttpDate(text: String): Long? =
    try {
        val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        format.timeZone = TimeZone.getTimeZone("GMT")
        if (text.isEmpty()) null else format.parse(text)?.time
    } catch (_: ParseException) {
        null
    }

/**
 * What a POST came back with, for the one caller (consent retry) that has to tell a 5xx or a
 * network error from a 4xx. [status] is the HTTP status, or 0 when no response arrived.
 */
@InternalIntemptApi
data class PostOutcome(val status: Int, val retryAfterMs: Long? = null) {
    val isSuccess: Boolean get() = status in HTTP_OK_FIRST..HTTP_OK_LAST
}

@Singleton
@InternalIntemptApi
class HttpManagerService
    @Inject
    constructor(
        private val config: ConfigManagerService,
        private val logger: LoggerManagerService,
    ) {
        /**
         * `by lazy`, not an eager initializer. Constructing this was **13.79 ms — 53% of the SDK's
         * entire 26 ms init**, and all of it on the host app's main thread inside
         * `Application.onCreate`, because `HttpClient {}` with no explicit engine runs
         * `ServiceLoader` engine discovery: classloading and reflection, not network.
         *
         * Nothing on the common path needs it. Event delivery goes through the vendored queue's
         * own `HttpURLConnection` transport (`queue/HttpService.java`), not through here. The only
         * two callers are [get]/[post] for the recommendation feed and for consent events, and both
         * already run inside a coroutine on `Dispatchers.IO` — so the cost does not merely move
         * later, it moves off the main thread entirely, and an app that never calls
         * `recommendation()` and records no consent event never pays it at all.
         *
         * The default `SYNCHRONIZED` mode is deliberate: `post` and `get` are `suspend` and can be
         * entered concurrently, and `PUBLICATION` would allow two clients to be built and one
         * silently discarded along with its connection pool.
         */
        internal val client by lazy {
            // Traced so the benchmark can show the cost MOVED rather than vanished. A drop in
            // Intempt.provideHttp on its own would look identical to R8 having stripped the client
            // entirely, which would be a bug reported as a win.
            traced("Intempt.httpClientInit") {
                // The Android engine named explicitly rather than found through ServiceLoader:
                // it is the only engine on the classpath, and naming it is what exposes the
                // engine block below.
                HttpClient(Android) {
                    // INT-3945: the same opt-in certificatePins the event queue uses. Without this
                    // a host app that enabled pinning had it on events and not on consent,
                    // optimization, feeds or the push webhook, all of which carry the write key.
                    // No pins (the default) leaves the connection on platform TLS, untouched.
                    engine {
                        CertificatePinning.sslSocketFactoryFor(config.certificatePins)?.let { pinned ->
                            sslManager = { connection -> connection.sslSocketFactory = pinned }
                        }
                    }
                    // INT-3902: without this a stalled connection holds the coroutine indefinitely.
                    install(HttpTimeout) {
                        requestTimeoutMillis = REQUEST_TIMEOUT_MS
                        connectTimeoutMillis = CONNECT_TIMEOUT_MS
                        socketTimeoutMillis = SOCKET_TIMEOUT_MS
                    }
                    install(ContentNegotiation) {
                        json(
                            Json {
                                prettyPrint = true
                                isLenient = true
                            },
                        )
                    }
                }
            }
        }

        suspend fun post(
            url: String,
            body: JSONObject,
            type: ContentType = ContentType.Application.Json,
        ): HttpResponse? {
            return try {
                val authHeader = "Basic ${config.token()}"

                // INT-3914: the body is redacted too (userId, email, attributes, captured text); size only.
                // Redacted. This used to print the full `Authorization: Basic <base64>` value, so
                // any host app that enabled logging — or called Intempt.Logging.start() — wrote the
                // project's ingestion credential into logcat, where it reaches bug reports and
                // crash-reporter log tails. The key is extractable from the APK anyway, but writing
                // it into every user's device log is a different exposure, and SECURITY.md did not
                // mention it.
                logger.log(
                    """
                    POST Request:
                    URL: $url
                    Headers:
                        Authorization: Basic <redacted>
                        Content-Type: $type
                    Body: <redacted, ${body.toString().length} chars>
                    """.trimIndent(),
                )

                val response =
                    client.post(url) {
                        contentType(type)
                        setBody(body.toString())
                        header(HttpHeaders.Authorization, authHeader)
                    }
                if (response.status.value in 200..299) {
                    logger.log("POST | URL: $url. Status: ${response.status.value}")
                    response
                } else {
                    throw Exception("Failed with status code: ${response.status.value}")
                }
            } catch (e: Exception) {
                logger.error("HttpService post request error: ${e.message}")
                null
            }
        }

        /**
         * Like [post], but reports the status instead of collapsing every failure into null.
         * [post] is unchanged and keeps its callers' behaviour; this exists for consent retry.
         */
        suspend fun postForStatus(
            url: String,
            body: JSONObject,
            type: ContentType = ContentType.Application.Json,
        ): PostOutcome {
            return try {
                val response =
                    client.post(url) {
                        contentType(type)
                        setBody(body.toString())
                        header(HttpHeaders.Authorization, "Basic ${config.token()}")
                    }
                val status = response.status.value
                logger.log("POST | URL: $url. Status: $status")
                val retryAfterMs =
                    parseRetryAfterMs(response.headers[HttpHeaders.RetryAfter], System.currentTimeMillis())
                PostOutcome(status, retryAfterMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("HttpService post request error: ${e.message}")
                PostOutcome(0)
            }
        }

        suspend fun get(url: String): HttpResponse {
            return try {
                client.get(url)
            } catch (e: Exception) {
                logger.error("HttpService get request error: ${e.message}")
                // INT-3907 — rethrow the cause; a bare Exception() hid what failed.
                throw e
            }
        }

        internal companion object {
            const val REQUEST_TIMEOUT_MS = 30_000L
            const val CONNECT_TIMEOUT_MS = 10_000L
            const val SOCKET_TIMEOUT_MS = 30_000L
        }
    }
