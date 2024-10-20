package com.intempt.intempt_android

import android.content.Context
import com.intempt.intempt_android.types.ConfigKeys
import com.intempt.intempt_android.types.IntemptConfigs
import java.io.InputStream
import org.json.JSONObject

data class ConfigManager(val context: Context) {
    private val apiKey: String
    private val sourceId: String;
    private val organizationId: String;
    private val projectId: String;


    init {
        val configs = getConfigs()
        if (configs == null) {
            throw Exception("Error reading config file")
        }
        else{
            this.apiKey = configs.apiKey
            this.sourceId = configs.sourceId
            this.organizationId = configs.organizationId
            this.projectId = configs.projectId
        }


    }

    private fun getConfigs(): IntemptConfigs? {
        return try {
            val inputStream: InputStream = context.assets.open("intempt-config.json")
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()

            val json = String(buffer, Charsets.UTF_8)

            // Parse the JSON string into a JSONObject
            val jsonObject = JSONObject(json)
            val authObject = jsonObject.getJSONObject(ConfigKeys.Auth.key)

            IntemptConfigs(
                apiKey = authObject.getString(ConfigKeys.ApiKey.key),
                sourceId = authObject.getString(ConfigKeys.SourceId.key),
                organizationId = authObject.getString(ConfigKeys.OrganizationId.key),
                projectId = authObject.getString(ConfigKeys.ProjectId.key)
            )
        }
        catch (e: Exception) {
            e.printStackTrace()
            null
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