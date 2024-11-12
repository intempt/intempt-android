package com.intempt.core.services

import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.types.ConfigKeys
import com.intempt.core.types.ConfigResult
import com.intempt.core.types.Constants
import com.intempt.core.types.DefaultConfigs
import com.intempt.core.types.IntemptConfigs
import com.intempt.core.types.IntemptOptions
import java.io.InputStream
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import java.util.Base64


@Singleton
class ConfigManagerService  @Inject constructor(
    private val context: Context,
):BaseComponent() {
    private val _apiKey: String
    private val _sourceId: String;
    private val _organizationId: String;
    private val _projectId: String;

    var isLoggingEnabled : Boolean;
    var isUserOptIn: Boolean;
    var isQueueEnabled: Boolean;
    val itemsInQueue: Int;
    val timeBuffer: Long;


    private val _isTouchEnabled: Boolean;
    private val _isTextCaptureEnabled: Boolean;
    private val _isAutoCaptureEnabled: Boolean;

    val apiKey: String get() = _apiKey;
    val sourceId: String get() = _sourceId;
    val isTextCaptureEnabled: Boolean get() = _isTextCaptureEnabled;
    val isTouchEnabled: Boolean get() = _isTouchEnabled;
    val isAutoCaptureEnabled: Boolean get() = _isAutoCaptureEnabled;



    val consentUrl:String
        get() = "${Constants.API_URL}/${_organizationId}/projects/${_projectId}/consents/data"

    val eventsUrl:String
        get() = "${Constants.API_URL}/${_organizationId}/projects/${_projectId}/sources/${_sourceId}/track"

    val optimizationUrl:String
        get() = "${Constants.API_URL}/${_organizationId}/projects/${_projectId}/optimization/choose-api"



    init {
        val (configs, options) = getConfigs()

        _apiKey = configs?.apiKey ?: ""
        _sourceId = configs?.sourceId ?: ""
        _organizationId = configs?.organizationId ?: ""
        _projectId = configs?.projectId ?: ""


        _isTouchEnabled = options?.isTouchEnabled ?: DefaultConfigs.IsTouchEnabled.value
        _isTextCaptureEnabled = options?.isTextCaptureEnabled ?: DefaultConfigs.IsTextCaptureEnabled.value
        _isAutoCaptureEnabled = options?.isAutoCaptureEnabled ?: DefaultConfigs.IsAutoCaptureEnabled.value

        itemsInQueue = options?.itemsInQueue ?: DefaultConfigs.ItemsInQueue.value
        timeBuffer = options?.timeBuffer ?: DefaultConfigs.TimeBuffer.value
        isUserOptIn = DefaultConfigs.IsUserOptIn.value
        isLoggingEnabled = options?.isLoggingEnabled ?: DefaultConfigs.IsLoggingEnabled.value
        isQueueEnabled = options?.isQueueEnabled ?: DefaultConfigs.IsQueueEnabled.value
    }

    fun token(): String {
        if (_apiKey.isEmpty()) return ""
        val (username, password) = _apiKey.split(".")
        return Base64.getEncoder().encodeToString("$username:$password".toByteArray())
    }

    private fun getConfigs(): ConfigResult {
        return try {
            val inputStream: InputStream = context.assets.open("intempt-config.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()

            val json = String(buffer, Charsets.UTF_8)

            val jsonObject = JSONObject(json)
            val authObject = jsonObject.getJSONObject(ConfigKeys.Auth.key)
            val optionsObject = jsonObject.getJSONObject(ConfigKeys.Options.key)

            val configs = IntemptConfigs(
                apiKey = authObject.getString(ConfigKeys.ApiKey.key),
                sourceId = authObject.getString(ConfigKeys.SourceId.key),
                organizationId = authObject.getString(ConfigKeys.OrganizationId.key),
                projectId = authObject.getString(ConfigKeys.ProjectId.key)
            )

            val options = IntemptOptions(
                isLoggingEnabled = optionsObject.optBoolean(ConfigKeys.IsLoggingEnabledOpt.key, false),
                isTouchEnabled = optionsObject.optBoolean(ConfigKeys.IsTouchEnabledOpt.key, true),
                isTextCaptureEnabled = optionsObject.optBoolean(ConfigKeys.IsTextCaptureEnabled.key, true),
                isQueueEnabled = optionsObject.optBoolean(ConfigKeys.IsQueueEnabled.key, true),
                isAutoCaptureEnabled = optionsObject.optBoolean(ConfigKeys.IsAutoCaptureEnabled.key, true),
                itemsInQueue = optionsObject.optInt(ConfigKeys.ItemsInQueue.key, 5),
                timeBuffer = optionsObject.optLong(ConfigKeys.TimeBuffer.key, 5000)
            )

            ConfigResult(configs, options)
        }
        catch (e: Exception) {
            ConfigResult(configs = null, options = null)
        }

    }
}