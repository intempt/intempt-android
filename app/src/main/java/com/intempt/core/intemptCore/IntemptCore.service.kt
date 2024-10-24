package com.intempt.core.intemptCore

import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.Logger
import com.intempt.core.services.eventPool.EventPool
import com.intempt.core.types.AutoCaptureParam
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.ScreenEventProps
import com.intempt.core.types.UiEventProps
import javax.inject.Inject

internal class IntemptCoreService  @Inject constructor(
    private val eventPool: EventPool,
    private val configManagerService: ConfigManagerService,
    private val autoCaptureComponent: AutoCaptureComponent
) {






    internal fun autoCapture(listenerType: String, param: AutoCaptureParam ){
        Logger.log("autoCapture | Is $listenerType listener")

        val payload:DispatchEventProps = when (param) {
            is UiEventProps -> onUiEventReceive(listenerType, param)
            is ScreenEventProps -> onScreenEventReceive(param)
        }

       eventPool.dispatchEvent(payload)
    }

    private  fun onUiEventReceive(
        listenerType: String,
        props: UiEventProps
    ):DispatchEventProps {
        Logger.log("autoCapture | Is UiEventProps")
        val (activity, view) = props;

        val eventName = when (listenerType) {
            "change" -> Constants.CHANGE.EVENT_NAME
            else -> Constants.TOUCH.EVENT_NAME

        }

        val entityName = when (listenerType) {
            "change" -> Constants.CHANGE.ENTITY_NAME
            else -> Constants.TOUCH.ENTITY_NAME
        }

        return DispatchEventProps(
            eventName = eventName,
            entityName = entityName,
            type = listenerType,
            event = null,
            context = activity,
            view = view
        )
    }

    private fun onScreenEventReceive(props:ScreenEventProps):DispatchEventProps{
        val (activity,eventName,entityName, eventType) = props
        return DispatchEventProps(
            eventName = eventName,
            entityName = entityName,
            type = eventType,
            event = null,
            context = activity
        )
    }

}