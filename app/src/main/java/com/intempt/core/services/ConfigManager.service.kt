package com.intempt.core.services

import android.content.Context
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.types.ConfigKeys
import com.intempt.core.types.ConfigResult
import com.intempt.core.types.IntemptConfigs
import com.intempt.core.types.IntemptOptions
import java.io.InputStream
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class ConfigManagerService  @Inject constructor(
    private val context: Context
):BaseComponent() {
    private val apiKey: String
    private val sourceId: String;
    private val organizationId: String;
    private val projectId: String;

    private val _isLoggingEnabled : Boolean;
    private val _isTouchEnabled: Boolean;
    private val _isTextCaptureEnabled: Boolean;
    private val _isQueueEnabled: Boolean;
    private val _isAutoCaptureEnabled: Boolean;
    private val _itemsInQueue: Int;
    private val _timeBuffer: Long;



    val isTextCaptureEnabled: Boolean get() = _isTextCaptureEnabled;
    val isLoggingEnabled: Boolean get() = _isLoggingEnabled;
    val isTouchEnabled: Boolean get() = _isTouchEnabled;
    val isQueueEnabled: Boolean get() = _isQueueEnabled;
    val isAutoCaptureEnabled: Boolean get() = _isAutoCaptureEnabled;
    val itemsInQueue: Int get() = _itemsInQueue;
    val timeBuffer: Long get() = _timeBuffer;


    init {
        val (configs, options) = getConfigs()
//        if (configs == null) {
//            Logger.log("Error reading config file")
//        }
//        else{
            apiKey = configs?.apiKey ?: ""
            sourceId = configs?.sourceId ?: ""
            organizationId = configs?.organizationId ?: ""
            projectId = configs?.projectId ?: ""

            _isLoggingEnabled = options?.isLoggingEnabled ?: false
            _isTouchEnabled = options?.isTouchEnabled ?: true
            _isTextCaptureEnabled = options?.isTextCaptureEnabled ?: true
            _isQueueEnabled = options?.isQueueEnabled ?: true
            _isAutoCaptureEnabled = options?.isAutoCaptureEnabled ?: true
            _itemsInQueue = options?.itemsInQueue ?: 5
            _timeBuffer = options?.timeBuffer ?: 5000
//        }

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
           Logger.log("Error reading config file: ${e.message}")
            ConfigResult(configs = null, options = null)
        }

    }

    override fun toString(): String {
        val output = """
            {
                apiKey: '$apiKey',
                sourceId: '$sourceId',
                organizationId: '$organizationId',
                projectId: '$projectId'
            }
        """
        return output.trimIndent()
    }
}