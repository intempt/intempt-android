package com.intempt.core.autocapture.lifecycleCallbacksTracker

import android.app.Activity
import androidx.fragment.app.Fragment
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import javax.inject.Inject

internal class ScreenTrackerService @Inject constructor(
    private val eventSrv: EventPoolManagerService,
    val logger: LoggerManagerService,
    private val utils: UtilsService,
    private val storage: StorageManagerService,
) {

    fun handleFragmentCallbacks(callBackName:String, key:String, fragment: Fragment) {
        storeFragmentName(key, fragment)
        logger.log("ScreenTracker | $callBackName: ${fragment::class.java.simpleName}")
    }

    fun storePageId(){
        return storage.setStorageItem(
            prefs = StorageKeys.SessionPrefs.key,
            key = StorageKeys.PageId.key,
            value = utils.generateId(IdTypeKeys.PageId.key)
        ){ key, value ->
            putString(key, value)
        }
    }

    fun logAndDispatch(
        activity: Activity,
        eventName:String,
        entityName:String,
        eventType:String,
        viewType: String,
    ) {
        val errorMessage = "ScreenTracker | $viewType Error handling: ${activity.localClassName}";
        utils.withTryCatch(errorMessage) {
            eventSrv.dispatchEvent(
                DispatchEventProps(
                    eventName = eventName,
                    entityName = entityName,
                    type = eventType ,
                    context = activity
                )
            )
        }
    }

    private fun storeFragmentName(key:String, fragment: Fragment){
        return storage.setStorageItem(
            prefs = StorageKeys.FragmentPrefs.key,
            key,
            value = fragment.javaClass.simpleName
        ){ _, value ->
            putString(key, value)
        }
    }




}