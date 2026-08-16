package com.intempt.core.services.eventPool

import android.app.Activity
import android.view.View
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.firebase.FirebaseService
import com.intempt.core.types.HandleEventTypeProps
import com.intempt.core.types.IntemptEventProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class EventHandlers(
    private val logger: LoggerManagerService,
    private val intemptEvent: IntemptEventManagerService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val firebaseService: FirebaseService = FirebaseService()

    fun fragment(props: HandleEventTypeProps): Array<IntemptEventProvider> {
        val newEvent = intemptEvent.generateFragmentTransitionEventPayload()
        return newEvent ?: arrayOf()
    }

    fun screen(props: HandleEventTypeProps): Array<IntemptEventProvider> {
        val newEvent =
            intemptEvent.generateScreenViewEventPayload(
                props.context as Activity,
                props.entityName,
            )
        return newEvent ?: arrayOf()
    }

    fun touch(props: HandleEventTypeProps): Array<IntemptEventProvider> {
        logger.log("EventPool | Touch called")

        val view: View = props.view!!

        logger.log("EventPool | ${view.javaClass.name}")

        val newEvent =
            intemptEvent.generateUiElementEventPayload(
                view,
            )

        logger.log("EventPool | Touch Event: $newEvent")
        return newEvent ?: arrayOf()
    }

    fun change(props: HandleEventTypeProps): Array<IntemptEventProvider> {
        logger.log("EventPool | Change called")
        val view: View = props.view!!
        val newEvent =
            intemptEvent.generateUiElementEventPayload(
                view,
            )

        logger.log("EventPool | Change Event: $newEvent")
        return newEvent ?: arrayOf()
    }

    /**
     * suspend, not CompletableFuture. This was the SDK's only use of CompletableFuture, and it
     * set the minSdk floor at 24 — the class does not exist below it. A customer on API 23
     * could not add the SDK at all:
     *
     *   uses-sdk:minSdkVersion 23 cannot be smaller than version 31 declared in library
     *
     * The work here was always a coroutine; `future {}` only wrapped it to hand a
     * CompletableFuture to the caller. Returning the value directly drops the API 24
     * dependency with no change in behaviour and no scope of its own to leak.
     */
    suspend fun installOrUpgrade(props: HandleEventTypeProps): Array<IntemptEventProvider> {
        logger.log("EventPool | InstallOrUpgrade called")

        val token = withContext(dispatcher) { firebaseService.initializeToken() }
        val newEvent = intemptEvent.generateInstallUpgradeEventPayload(token = token)

        logger.log("EventPool | App installation/upgrade Event: $newEvent")
        return newEvent
    }
}
