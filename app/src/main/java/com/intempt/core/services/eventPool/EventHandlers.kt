package com.intempt.core.services.eventPool

import android.app.Activity
import android.view.View
import com.intempt.core.services.firebase.FirebaseService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.IntemptEventProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withContext
import java.util.concurrent.CompletableFuture


internal class EventHandlers(
    private val logger: LoggerManagerService,
    private val intemptEvent: IntemptEventManagerService,
    private val firebaseService: FirebaseService = FirebaseService()
) {
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun fragment(props: HandleEventTypeProps):Array<IntemptEventProvider> {
        val newEvent = intemptEvent.generateFragmentTransitionEventPayload()
        return newEvent ?: arrayOf()
    }

    fun screen(props: HandleEventTypeProps):Array<IntemptEventProvider> {
        val newEvent = intemptEvent.generateScreenViewEventPayload(
            props.context as Activity,
            props.entityName
        )
        return newEvent ?: arrayOf()
    }

    fun touch(props: HandleEventTypeProps):Array<IntemptEventProvider> {
        logger.log("EventPool | Touch called")

        val view: View = props.view!!

        logger.log("EventPool | ${view.javaClass.name}")

        val newEvent = intemptEvent.generateUiElementEventPayload(
            view
        )

        logger.log("EventPool | Touch Event: $newEvent")
        return newEvent ?: arrayOf()
    }

    fun change(props: HandleEventTypeProps):Array<IntemptEventProvider> {
            logger.log("EventPool | Change called");
            val view: View = props.view!!
            val newEvent = intemptEvent.generateUiElementEventPayload(
                view
            )

            logger.log("EventPool | Change Event: $newEvent");
            return newEvent ?: arrayOf()
    }

    fun installOrUpgrade(props: HandleEventTypeProps): CompletableFuture<Array<IntemptEventProvider>> {
        logger.log("EventPool | InstallOrUpgrade called")

        return coroutineScope.future {
            val token = withContext(Dispatchers.IO) { firebaseService.initializeToken() }
            val newEvent = intemptEvent.generateInstallUpgradeEventPayload(token = token)

            logger.log("EventPool | App installation/upgrade Event: $newEvent")
            newEvent
        }
    }

}