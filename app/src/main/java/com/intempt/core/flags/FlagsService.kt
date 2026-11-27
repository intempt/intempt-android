@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.flags

import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.types.FlagContext
import com.intempt.core.types.FlagDetail
import com.intempt.core.types.FlagError
import com.intempt.core.types.FlagErrorReporter
import com.intempt.core.types.FlagMap
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The flag module's Android side (F33): wires [FlagEvaluator] to the shared transport, the stored
 * identity and the process lifecycle. Imports only core services, never tracking; see
 * `FlagsModuleBoundaryTest`.
 */
@Singleton
internal class FlagsService
    @Inject
    constructor(
        private val config: ConfigManagerService,
        private val http: HttpManagerService,
        private val storage: StorageManagerService,
        private val logger: LoggerManagerService,
    ) {
        // The handler keeps a failure in a background flag job (stream, exposure) from reaching the
        // host app's default handler, which on Android kills the process.
        private val scope =
            CoroutineScope(
                SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler { _, e -> logger.debug("flags: background job failed: ${e.message}") },
            )

        private val evaluator =
            FlagEvaluator(
                post = { route, body ->
                    val response =
                        http.postChoose(config.flagsUrl(route), JSONObject(body), HttpManagerService.CHOOSE_TIMEOUT_MS)
                    FlagHttpResponse(response.status, response.body, response.timedOut)
                },
                identity = { context ->
                    FlagIdentity(
                        sourceId = config.sourceId,
                        profileId = context.profileId ?: storage.getProfileId(),
                        userId = context.userId,
                        sessionId = storage.getSessionId(),
                    )
                },
                openStream = ::openStream,
                schedule = { delayMs, action ->
                    val job =
                        scope.launch {
                            delay(delayMs)
                            action()
                        }
                    Cancellable { job.cancel() }
                },
                launch = { block -> scope.launch { block() } },
                reporter = FlagErrorReporter({ config.sourceId }, { logger.error(it) }, { logger.debug(it) }),
                warn = { logger.warn(it) },
            )

        init {
            // addObserver must run on the main thread; initialize() may be called from any thread.
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    ProcessLifecycleOwner.get().lifecycle.addObserver(
                        object : DefaultLifecycleObserver {
                            override fun onStop(owner: LifecycleOwner) = evaluator.pause()
                        },
                    )
                }.onFailure { logger.debug("flags: no process lifecycle, the stream stays open: ${it.message}") }
            }
        }

        var onFlagError: ((FlagError) -> Unit)?
            get() = evaluator.reporter.hook
            set(value) {
                evaluator.reporter.hook = value
            }

        suspend fun detail(
            key: String,
            context: FlagContext,
            defaultValue: Any?,
            disableExposure: Boolean = false,
        ): FlagDetail = evaluator.detail(key, context, defaultValue, disableExposure)

        suspend fun all(context: FlagContext): FlagMap = evaluator.all(context)

        fun forget() = evaluator.forget()

        /**
         * `/flags/stream` on the shared client, with the request timeout lifted: the client-wide
         * 30 s would cut the stream every 30 s. The socket (read) timeout stays, above the server's
         * 15 s heartbeat, so a silent dead connection is noticed and reconnected.
         */
        private fun openStream(handlers: StreamHandlers): StreamHandle {
            val job =
                scope.launch {
                    try {
                        http.client.prepareGet(config.flagsUrl(FlagRoute.STREAM)) {
                            header(HttpHeaders.Authorization, "Basic ${config.token()}")
                            header(HttpHeaders.Accept, "text/event-stream")
                            timeout {
                                requestTimeoutMillis = HttpTimeout.INFINITE_TIMEOUT_MS
                                socketTimeoutMillis = STREAM_READ_TIMEOUT_MS
                            }
                        }.execute { response ->
                            if (response.status.value != HTTP_OK) {
                                logger.debug("flags: stream answered ${response.status.value}")
                                return@execute
                            }
                            handlers.onOpen()
                            val channel = response.bodyAsChannel()
                            val parser = SseParser()
                            while (true) {
                                val line = channel.readUTF8Line() ?: break
                                parser.feed(line)?.let { handlers.onMessage(it.event, it.data) }
                            }
                        }
                    } catch (e: IOException) {
                        logger.debug("flags: stream closed: ${e.message}")
                    } finally {
                        handlers.onClose()
                    }
                }
            return StreamHandle { job.cancel() }
        }

        private companion object {
            const val HTTP_OK = 200
            const val STREAM_READ_TIMEOUT_MS = 45_000L
        }
    }
