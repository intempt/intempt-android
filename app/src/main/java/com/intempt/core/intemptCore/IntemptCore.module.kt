@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.intemptCore

import android.content.Context
import com.intempt.core.autocapture.AutoCaptureModule
import com.intempt.core.customCapture.CustomCaptureModule
import com.intempt.core.internal.traced
import com.intempt.core.queue.ConsentAuditLog
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.queue.QueueConfig
import com.intempt.core.services.CertificatePinning
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.InstanceId
import com.intempt.core.types.IntemptCredentials
import com.intempt.core.types.IntemptRuntimeOptions
import com.intempt.core.types.IntemptError
import dagger.Component
import dagger.Module
import dagger.Provides
import javax.inject.Singleton

@Module(
    includes = [
        AutoCaptureModule::class,
        CustomCaptureModule::class,
    ],
)
internal class IntemptCoreModule(
    private val consumerContext: Context,
    /** Null means "read assets/intempt-config.json", which is the pre-3.0 behaviour. */
    private val runtimeCredentials: IntemptCredentials? = null,
    /**
     * Options supplied at runtime, overriding the asset file per field. Null means the asset file
     * decides everything, which is the pre-3.0 behaviour.
     */
    private val runtimeOptions: IntemptRuntimeOptions? = null,
    /**
     * Which named instance this graph belongs to.
     *
     * Dagger already gives one graph per `initialize()`, so this exists for the part Dagger cannot
     * scope: the `SharedPreferences` files and the SQLite queue, which live on disk and are shared
     * by name.
     */
    private val instance: InstanceId = InstanceId.Default,
) {
    @Provides
    @Singleton
    fun provideInstanceId(): InstanceId = instance

    private companion object {
        // Mirrors EventDbAdapter.DATABASE_NAME and ConsentAuditLog's. Duplicated rather than
        // exported: both are private to the vendored package, and widening their visibility to
        // share a string would put two database names in the public surface.
        const val QUEUE_DATABASE_NAME = "intempt_events"
        const val CONSENT_DATABASE_NAME = "intempt_consent_audit"
    }

    /**
     * Provided as nullable so ConfigManagerService can be constructed either way without two
     * graphs. Dagger needs the binding to exist even when the value is absent.
     */
    @Provides
    @Singleton
    fun provideRuntimeCredentials(): IntemptCredentials? = runtimeCredentials

    /** Nullable for the same reason as [provideRuntimeCredentials]: the binding must exist. */
    @Provides
    @Singleton
    fun provideRuntimeOptions(): IntemptRuntimeOptions? = runtimeOptions

    @Provides
    fun provideContext(): Context {
        return consumerContext.applicationContext
    }

    @Provides
    @Singleton
    fun provideUtilsService(logger: LoggerManagerService): UtilsService {
        return UtilsService(logger)
    }

    @Provides
    @Singleton
    fun provideHttpService(
        config: ConfigManagerService,
        logger: LoggerManagerService,
    ): HttpManagerService =
        traced("Intempt.provideHttp") {
            HttpManagerService(config, logger)
        }

    @Provides
    @Singleton
    fun provideLoggerService(config: ConfigManagerService): LoggerManagerService {
        return LoggerManagerService(config)
    }

    @Provides
    @Singleton
    fun provideStorageManagerService(utils: UtilsService): StorageManagerService =
        traced("Intempt.provideStorage") {
            StorageManagerService(
                consumerContext.applicationContext,
                utils,
                instance = instance,
            )
        }

    @Provides
    @Singleton
    fun provideEventPoolManagerService(
        config: ConfigManagerService,
        logger: LoggerManagerService,
        http: HttpManagerService,
        intemptEvent: IntemptEventManagerService,
        delivery: DeliveryMessages,
    ): EventPoolManagerService =
        traced("Intempt.provideEventPool") {
            EventPoolManagerService(
                config,
                logger,
                http,
                intemptEvent,
                delivery,
                consentAudit =
                    ConsentAuditLog(
                        consumerContext.applicationContext,
                        instance.scope(CONSENT_DATABASE_NAME),
                    ),
            )
        }

    @Provides
    @Singleton
    fun provideQueueConfig(
        config: ConfigManagerService,
        logger: LoggerManagerService,
    ): QueueConfig {
        // The Basic token, built exactly as HttpManagerService builds it for every other
        // request. Without it the vendored transport posts unauthenticated and the gateway's
        // 401 is treated as an unrecoverable status, so the batch is deleted rather than
        // retried — a silent 100% loss.
        return traced("Intempt.provideQueueConfig") {
            QueueConfig(
                config.eventsUrl,
                "Basic ${config.token()}",
                null,
                false,
                config.itemsInQueue,
                config.timeBuffer,
                // Opt-in: null (no pinning, platform-default TLS) unless the host app configured
                // certificatePins in intempt-config.json.
                CertificatePinning.sslSocketFactoryFor(config.certificatePins),
            ).also {
                it.setLoggingEnabled(config.isLoggingEnabled)

                // One destination for the whole SDK. Installed here because this is the only place that
                // already wires the queue's logging, and doing it from LoggerManagerService's own
                // constructor would mean any instance built in a test silently took over the static
                // sink for every other test in the JVM.
                val sink = logger.asQueueSink()
                it.setLogSink { priority, tag, message, throwable ->
                    sink(priority, tag, message, throwable)
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideDeliveryMessages(
        queueConfig: QueueConfig,
        errors: ErrorReporter,
    ): DeliveryMessages {
        // One per named instance, owned here. The vendored class had a static registry keyed by
        // project token to partition a shared queue across Mixpanel instances; @Singleton on this
        // component replaces it, and the scoped database name keeps two instances off one SQLite
        // file — two writers on one file is how a second instance deletes the first's queue.
        return traced("Intempt.provideDelivery") {
            DeliveryMessages(
                consumerContext.applicationContext,
                queueConfig,
                instance.scope(QUEUE_DATABASE_NAME),
            ).also {
                // The delivery half of the error taxonomy. Refused-at-the-call-site errors reach the
                // listener from the capture components; without this, every failure that happens after
                // an event is accepted — offline, 401, 5xx — would be invisible to a host app, which is
                // the half where events are actually lost.
                it.setDeliveryFailureListener { status, description, retryAfterMillis, terminal ->
                    errors.report(
                        when {
                            // No usable status: the request never got an answer, so this is a transport
                            // failure rather than a rejection.
                            status <= 0 -> IntemptError.Transport(description)
                            terminal -> IntemptError.Terminal(status)
                            else -> IntemptError.Retryable(status, retryAfterMillis.takeIf { ms -> ms > 0 })
                        },
                    )
                }
            }
        }
    }

    @Provides
    @Singleton
    fun provideIntemptEventManagerService(
        storage: StorageManagerService,
        utils: UtilsService,
        config: ConfigManagerService,
    ): IntemptEventManagerService =
        traced("Intempt.provideEventManager") {
            IntemptEventManagerService(
                consumerContext.applicationContext,
                storage,
                utils,
                config,
            )
        }
}

@Singleton
@Component(modules = [IntemptCoreModule::class])
internal interface IntemptCoreComponent {
    fun initService(): IntemptCoreService

    /**
     * The resolved configuration, so [com.intempt.core.Intempt.initialize] can check that
     * credentials were actually found before reporting the SDK as running. Without this the
     * facade could only observe that Dagger wired up, which it does whether or not
     * intempt-config.json exists.
     */
    fun config(): ConfigManagerService

    @Component.Factory
    interface Factory {
        fun create(intemptModule: IntemptCoreModule): IntemptCoreComponent
    }
}
