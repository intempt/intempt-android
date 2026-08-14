package com.intempt.core.services

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
internal class HttpManagerService
    @Inject
    constructor(
        private val config: ConfigManagerService,
        private val logger: LoggerManagerService,
    ) {
        private val client =
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
