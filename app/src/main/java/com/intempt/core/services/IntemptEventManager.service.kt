package com.intempt.core.services

import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.DatePicker
import android.widget.EditText
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.TimePicker
import android.widget.ToggleButton
import com.intempt.core.eventModels.AliasEvent
import com.intempt.core.eventModels.BaseIntemptEvent
import com.intempt.core.eventModels.ConsentEvent
import com.intempt.core.eventModels.FragmentTransitionEvent
import com.intempt.core.eventModels.GroupEvent
import com.intempt.core.eventModels.IdentifyEvent
import com.intempt.core.eventModels.InstallOrUpgradeEvent
import com.intempt.core.eventModels.RecordEvent
import com.intempt.core.eventModels.ScreenViewEvent
import com.intempt.core.eventModels.SessionEvent
import com.intempt.core.eventModels.SessionUserAttributes
import com.intempt.core.eventModels.TrackEvent
import com.intempt.core.eventModels.UiElementEvent
import com.intempt.core.types.AppVisibilityState
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.IntemptEventProvider
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class IntemptEventManagerService @Inject constructor(
    private val context: Context,
    private val storage: StorageManagerService,
    private val utils: UtilsService,
    private val config: ConfigManagerService
) {

    private fun getDeviceType(): String {
        return when (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
            Configuration.SCREENLAYOUT_SIZE_SMALL, Configuration.SCREENLAYOUT_SIZE_NORMAL -> "Phone"
            Configuration.SCREENLAYOUT_SIZE_LARGE, Configuration.SCREENLAYOUT_SIZE_XLARGE -> "Tablet"
            else -> "Unknown"
        }
    }

    private fun getDeviceCarrier(): String {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.networkOperatorName?.toString() ?: ""
    }

    private fun getDevicePlatform(): String {
        return "Android ${android.os.Build.VERSION.RELEASE}"
    }

    private fun getBaseEventProps(): BaseIntemptEvent {
        return BaseIntemptEvent(
            eventId = utils.generateId(IdTypeKeys.EventId.key),
            sessionId = storage.getSessionId(),
            pageId = storage.getPageId(),
            profileId = storage.getProfileId()
        )
    }

    private fun getViewHierarchy(view: View): String {
        return utils.withTryCatch("Error getting view hierarchy"){
            val hierarchyList = mutableListOf<String>()
            var currentView: View? = view
            while (currentView != null) {
                hierarchyList.add(currentView.javaClass.simpleName)
                currentView = currentView.parent as? View
            }
            hierarchyList.reversed().joinToString(" -> ")
        }
    }

    private fun getViewTextValue(view: View):String {
        return utils.withTryCatch("Error getting text from view"){
            val text = (view as? TextView)?.text?.toString();
            val disabledText = "*****";

             if (!config.isTextCaptureEnabled) {
                disabledText
            } else {
                text ?: ""
            }
        }

    }

    private fun getViewValue(view: View): String {
        val disabledText = "*****";
        return if (!config.isTextCaptureEnabled) {
            disabledText
        } else {
            return utils.withTryCatch("Error getting value from view"){
                when (view) {
                    is CheckBox,
                    is RadioButton,
                    is ToggleButton,
                    is CompoundButton -> (view as CompoundButton).isChecked.toString()
                    is SeekBar -> view.progress.toString()
                    is Spinner -> view.selectedItem?.toString() ?: ""
                    is EditText -> view.text.toString()
                    is DatePicker -> "${view.month}-${view.dayOfMonth}-${view.year}"
                    is RatingBar -> view.rating.toString()
                    is TimePicker -> String.format(Locale("en", "US"),"%02d:%02d", view.hour, view.minute)
                    is ListView -> view.selectedItem?.toString() ?: ""
                    else -> ""
                }
            }
        }
    }

    private fun getCurrentVersionCode(): Int {
        val versionCode = context
            .packageManager
            .getPackageInfo(context.packageName, 0)
            .longVersionCode

        return (versionCode and 0xFFFFFFFF).toInt()
    }

    private fun getConsumerAppBuildType(): String? {
        return try {
            val buildConfigClass = Class.forName("${context.packageName}.BuildConfig")
            val buildTypeField = buildConfigClass.getField("BUILD_TYPE")
            buildTypeField.get(null) as String
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun generateSessionEventPayload(
        sessionStartEventName: String,
        ipAddress: String,
        city: String,
        region: String,
        country: String,
    ):Array<IntemptEventProvider>{
        val eventProps = getBaseEventProps()

        val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
        val appName = context.applicationInfo?.loadLabel(context.packageManager).toString()
        val appVersion = context.packageManager?.getPackageInfo(context.packageName, 0)?.versionName.toString()
        val appIdentifier = context.packageName
        val androidId = Settings.Secure.getString((context.contentResolver ?: "") as ContentResolver?, Settings.Secure.ANDROID_ID)

//        val userAttributes = SessionUserAttributes(
//            ipAddress = ipAddress,
//            city = city,
//            region = region,
//            country = country,
//            deviceType = getDeviceType(),
//            carrier = getDeviceCarrier(),
//            platform = getDevicePlatform(),
//        )

        return arrayOf(
            SessionEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                sessionStartEventName = sessionStartEventName,
                deviceName = deviceName,
                appName = appName,
                appVersion = appVersion,
                appIdentifier = appIdentifier,
                androidId = androidId,
                userAttributes = SessionUserAttributes(
                    ipAddress = ipAddress,
                    city = city,
                    region = region,
                    country = country,
                    deviceType = getDeviceType(),
                    carrier = getDeviceCarrier(),
                    platform = getDevicePlatform(),
                )
            )
        )
    }

    fun generateFragmentTransitionEventPayload():Array<IntemptEventProvider>{
        val eventProps = getBaseEventProps()

        val visibleFragment = storage.getFragmentName("visibleFragment") ?: ""
        val addedFragment = storage.getFragmentName("addedFragment") ?: ""
        val removedFragment = storage.getFragmentName("removedFragment") ?: ""

        return arrayOf(
            FragmentTransitionEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                visibleFragment = visibleFragment,
                addedFragment = addedFragment,
                removedFragment = removedFragment
            )
        )
    }

    fun generateScreenViewEventPayload(activity: Activity, entityName:String):Array<IntemptEventProvider>{
        val eventProps = getBaseEventProps()

        val activityName = activity.localClassName;
        val fullActivity = activity.javaClass.name ?: "";
        val screenName = activity.javaClass.name ?: "";
        var timeOnScreen:Long? = null;

        if (entityName == "screenLeave") {
            timeOnScreen = (System.currentTimeMillis() - storage.getPageTime()) / 1000
        }
        return arrayOf(
            ScreenViewEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                activity = activityName,
                fullActivity = fullActivity,
                screenName = screenName,
                timeOnScreen = timeOnScreen
            )
        )
    }

    fun generateUiElementEventPayload(view: View): Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()

        val targetElement = view.javaClass.simpleName
        val hierarchy = getViewHierarchy(view)
        val targetText = getViewTextValue(view)
        val targetValue = getViewValue(view)
        val targetClass = view.javaClass.name
        val targetId = view.resources.getResourceEntryName(view.id)
        val fullTargetId  = view.resources.getResourceName(view.id)

        return arrayOf(
            UiElementEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                targetElement = targetElement,
                hierarchy = hierarchy,
                targetText = targetText,
                targetValue = targetValue,
                targetClass = targetClass,
                targetId = targetId,
                fullTargetId = fullTargetId
            )
        )

    }

    fun generateInstallUpgradeEventPayload():Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()

        val currentVersionCode: Int = getCurrentVersionCode();
        val previousVersionCode: Int = storage.getStoredVersionCode();
        val previousBuildType: String = storage.getStoredBuildType() ?: "";
        val currentBuildType: String = getConsumerAppBuildType() ?: "";
        val appVisibilityState: AppVisibilityState = storage.getAppVisibilityState();
        val isUpgrade = currentVersionCode != previousVersionCode || currentBuildType != previousBuildType

        return arrayOf(
            InstallOrUpgradeEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                currentVersionCode = currentVersionCode,
                previousVersionCode = previousVersionCode,
                previousBuildType = previousBuildType,
                currentBuildType = currentBuildType,
                appVisibilityState = appVisibilityState,
                isUpgrade = isUpgrade
            )
        )
    }

    fun generateIdentifyEventPayload(
        userId: String,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ): Array<IntemptEventProvider> {

        val eventProps = getBaseEventProps()

        return arrayOf(
            IdentifyEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                userId = userId,
                userAttributes = userAttributes,
                data = data
            )
        )
    }

    fun generateGroupEventPayload(
        accountId: String,
        accountAttributes: Map<String, String>? = null
    ): Array<IntemptEventProvider> {

        val eventProps = getBaseEventProps()

        return arrayOf(
            GroupEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                accountId = accountId,
                accountAttributes = accountAttributes,
            )
        )
    }

    fun generateTrackEventPayload(
        data: Map<String, String>
    ): Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()

        return arrayOf(
            TrackEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                data = data
            )
        )
    }

    fun generateRecordEventPayload(
        accountId: String? = null,
        userId: String? = null,
        accountAttributes: Map<String, String>? = null,
        userAttributes: Map<String, String>? = null,
        data: Map<String, String>? = null,
    ): Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()

        return arrayOf(
            RecordEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                accountId = accountId,
                userId = userId,
                accountAttributes = accountAttributes,
                userAttributes = userAttributes,
                data = data
            )
        )
    }

    fun generateAliasEventPayload(
        userId: String,
        anotherUserId: String
    ): Array<IntemptEventProvider> {
        val eventProps = getBaseEventProps()
        return arrayOf(
            AliasEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                userId = userId,
                anotherUserId = anotherUserId
            )
        )
    }

    fun generateConsentEventPayload(
        action: String,
        email: String ? = null,
        message: String ? = null,
        category: String ? = null,
        validUntil: Long,
        sourceId: String,
    ): Array<IntemptEventProvider>{
        val eventProps = getBaseEventProps()
        return  arrayOf(
            ConsentEvent(
                eventId = eventProps.eventId,
                sessionId = eventProps.sessionId,
                pageId = eventProps.pageId,
                profileId = eventProps.profileId,
                action = action,
                email = email,
                message = message,
                category = category,
                sourceId = sourceId,
                validUntil = validUntil,
            )
        )
    }
}