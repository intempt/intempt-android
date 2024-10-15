package com.intempt.intempt_android

import android.app.Activity
import android.content.Context

data class DispatchEventProps(val eventName:String,val entityName:String, val event:BaseIntemptEvent? , val type:String,  val context: Context)

data class HandleEventTypeProps(val type:String,val entityName:String,  val context: Context)

data class ScreenViewProps(val activity: Activity, val entityName:String)

