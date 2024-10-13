package com.intempt.intempt_android.autocapture

import android.app.Activity
import android.util.Log
import android.view.MotionEvent
import android.view.Window
import androidx.appcompat.app.AppCompatActivity


class AutoCapture(private val activity: Activity) {


    init{
        captureTouchEvents()
    }


    private fun captureTouchEvents() {
        activity.window.callback = object : Window.Callback by activity.window.callback {
            override fun dispatchTouchEvent(event: MotionEvent?): Boolean {

                Log.d("AutoCapture", "Global touch event: $event")



                return activity.dispatchTouchEvent(event)
            }
        }
    }

}