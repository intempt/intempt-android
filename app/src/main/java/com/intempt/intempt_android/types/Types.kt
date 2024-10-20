package com.intempt.intempt_android.types

import android.app.Activity
import android.content.Context
import android.view.View
import com.intempt.intempt_android.ConfigManager
import com.intempt.intempt_android.autocapture.eventModels.BaseIntemptEvent

data class DispatchEventProps(val eventName:String, val entityName:String, val event: BaseIntemptEvent?, val type:String, val context: Context, val view: View? = null)

data class HandleEventTypeProps(val type:String,val entityName:String,  val context: Context,  val view: View? = null )

data class ScreenViewProps(val activity: Activity, val entityName:String)

data class IntemptInitProps(val context: Context, val config: ConfigManager)

data class IntemptConfigs(
    val apiKey: String,
    val sourceId: String,
    val organizationId: String,
    val projectId: String
)

data class IntemptConfigJson(
    val auth: IntemptConfigs,
    val options: Map<String, Any>  // Assuming options is a flexible structure
)