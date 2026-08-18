@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.services

import com.intempt.core.internal.InternalIntemptApi
import com.intempt.core.internal.traced
import io.ktor.client.HttpClient
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
import kotlinx.serialization.json.Json
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

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
        private val client by lazy {
            // Traced so the benchmark can show the cost MOVED rather than vanished. A drop in
            // Intempt.provideHttp on its own would look identical to R8 having stripped the client
            // entirely, which would be a bug reported as a win.
            traced("Intempt.httpClientInit") {
                HttpClient {
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
                    Body: $body
                    """.trimIndent(),
                )

                val response =
                    client.post(url) {
                        contentType(type)
                        setBody(body.toString())
                        header(HttpHeaders.Authorization, authHeader)
                    }
                if (response.status.value in 200..299) {
                    logger.log("POST | URL: $url. BODY: $body. Status: ${response.status.value}")
                    response
                } else {
                    throw Exception("Failed with status code: ${response.status.value}")
                }
            } catch (e: Exception) {
                logger.error("HttpService post request error: ${e.message}")
                null
            }
        }

        suspend fun get(url: String): HttpResponse {
            return try {
                client.get(url)
            } catch (e: Exception) {
                logger.error("HttpService get request error: ${e.message}")
                throw Exception()
            }
        }
    }
