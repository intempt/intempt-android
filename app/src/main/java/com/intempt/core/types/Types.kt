package com.intempt.core.types

import android.app.Activity
import android.content.Context
import android.view.View
import com.intempt.core.eventModels.BaseIntemptEvent

internal data class DispatchEventProps(val eventName:String, val entityName:String, val event: BaseIntemptEvent?, val type:String, val context: Context, val view: View? = null)

internal data class HandleEventTypeProps(val type:String,val entityName:String,  val context: Context,  val view: View? = null )

internal data class ScreenViewProps(val activity: Activity, val entityName:String)

internal data class IntemptConfigs(
    val apiKey: String,
    val sourceId: String,
    val organizationId: String,
    val projectId: String
)

internal data class IntemptOptions(
    val isLoggingEnabled : Boolean,
    val isTouchEnabled: Boolean,
    val isTextCaptureEnabled: Boolean,
    val isQueueEnabled: Boolean,
    val isAutoCaptureEnabled: Boolean,
    val itemsInQueue: Int,
    val timeBuffer: Long,

)

internal data class ConfigResult(
    val configs: IntemptConfigs?,
    val options: IntemptOptions?
)


//Public types
sealed class AutoCaptureParam

data class UiEventProps(val activity: Activity, val view: View, val listenerType:String):AutoCaptureParam();
data class ScreenEventProps(
    val activity: Activity,
    val eventName:String,
    val entityName:String,
    val eventType:String,
):AutoCaptureParam();

