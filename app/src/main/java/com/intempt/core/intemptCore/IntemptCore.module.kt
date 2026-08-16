@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.intemptCore

import android.content.Context
import com.intempt.core.Intempt
import com.intempt.core.autocapture.AutoCaptureModule
import com.intempt.core.customCapture.CustomCaptureModule
import com.intempt.core.queue.ConsentAuditLog
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.queue.QueueConfig
import com.intempt.core.services.CertificatePinning
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
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
) {
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
    ): HttpManagerService {
        return HttpManagerService(config, logger)
    }

    @Provides
    @Singleton
    fun provideLoggerService(config: ConfigManagerService): LoggerManagerService {
        return LoggerManagerService(config)
    }

    @Provides
    @Singleton
    fun provideStorageManagerService(utils: UtilsService): StorageManagerService {
        return StorageManagerService(
            consumerContext.applicationContext,
            utils,
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
    ): EventPoolManagerService {
        return EventPoolManagerService(
            config,
            logger,
            http,
            intemptEvent,
            delivery,
            consentAudit = ConsentAuditLog(consumerContext.applicationContext),
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
        return QueueConfig(
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

    @Provides
    @Singleton
    fun provideDeliveryMessages(queueConfig: QueueConfig): DeliveryMessages {
        // One instance, owned here. The vendored class had a static registry keyed by
        // project token to partition a shared queue across Mixpanel instances; Intempt
        // has one SDK instance per app, so Dagger's @Singleton replaces it.
        return DeliveryMessages(consumerContext.applicationContext, queueConfig)
    }

    @Provides
    @Singleton
    fun provideIntemptEventManagerService(
        storage: StorageManagerService,
        utils: UtilsService,
        config: ConfigManagerService,
    ): IntemptEventManagerService {
        return IntemptEventManagerService(
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
    fun inject(intempt: Intempt)

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
