package com.intempt.intempt_android

import android.app.Activity
import android.content.Context
import android.view.View

data class DispatchEventProps(val eventName:String,val entityName:String, val event:BaseIntemptEvent? , val type:String,  val context: Context, val view: View? = null)

data class HandleEventTypeProps(val type:String,val entityName:String,  val context: Context,  val view: View? = null )

data class ScreenViewProps(val activity: Activity, val entityName:String)

