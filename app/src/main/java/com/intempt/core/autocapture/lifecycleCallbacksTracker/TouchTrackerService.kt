@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core.autocapture.lifecycleCallbacksTracker

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import com.intempt.core.internal.traced
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class TouchTrackerService
    @Inject
    constructor(
        private val eventPool: EventPoolManagerService,
        private val config: ConfigManagerService,
        private val utils: UtilsService,
    ) {
        private val debounceDelay = Constants.DEBOUNCE_DELAY
        private val handler = Handler(Looper.getMainLooper())
        private val runnableWrapper: Array<Runnable?> = arrayOfNulls(1)

        fun register(activity: Activity) {
            if (!config.isTouchEnabled) {
                return
            }
            val originalCallback = activity.window.callback

            activity.window.callback =
                object : Window.Callback by originalCallback {
                    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
                        if (event?.action == MotionEvent.ACTION_UP) {
                            // Both sections are on the main thread, inside the host app's touch
                            // dispatch, and neither is debounced — the debounce below only defers
                            // the event emission, not this. Split in two because the tree walk is
                            // the part that scales with the host's view hierarchy and the
                            // bookkeeping is the part that does not; one combined number could not
                            // tell a deep-hierarchy regression from anything else.
                            traced("Intempt.touchDispatch") {
                                val rootView = activity.window.decorView
                                val touchedView =
                                    traced("Intempt.findTouchedView") {
                                        findTouchedView(rootView, event.rawX.toInt(), event.rawY.toInt())
                                    }
                                runnableWrapper[0] =
                                    debounceAndLog(
                                        handler,
                                        runnableWrapper[0],
                                        touchedView,
                                        activity,
                                    )
                            }
                        }

                        return originalCallback.dispatchTouchEvent(event)
                    }
                }
        }

        private fun findTouchedView(
            view: View,
            x: Int,
            y: Int,
        ): View? {
            if (view !is ViewGroup) {
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                val viewX = location[0]
                val viewY = location[1]
                return if (x >= viewX && x <= viewX + view.width && y >= viewY && y <= viewY + view.height) {
                    view
                } else {
                    null
                }
            } else {
                for (i in 0 until view.childCount) {
                    val child = view.getChildAt(i)
                    val touchedView = findTouchedView(child, x, y)
                    if (touchedView != null) {
                        return touchedView
                    }
                }
            }
            return null
        }

        private fun debounceAndLog(
            handler: Handler,
            currentRunnable: Runnable?,
            view: View?,
            activity: Activity,
        ): Runnable {
            return utils.debounce(handler, debounceDelay, currentRunnable) {
                if (view !== null) {
                    eventPool.dispatchEvent(
                        DispatchEventProps(
                            eventName = Constants.TOUCH.EVENT_NAME,
                            entityName = Constants.TOUCH.ENTITY_NAME,
                            type = Constants.TOUCH.EVENT_TYPE,
                            event = null,
                            context = activity,
                            view = view,
                        ),
                        "TouchTrackerService",
                    )
                }
            }
        }
    }
