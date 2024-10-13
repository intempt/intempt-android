package com.intempt.intempt_android

import android.content.Context



data class ConfigManager(val context: Context) {
    private val apiKey: String
    private val sourceId: String;
    private val organizationId: String;
    private val projectId: String;


    init {
        fun getBuildConfigValue(context: Context, fieldName: String): String {
            val buildConfigClass = Class.forName(context.packageName + ".BuildConfig")
            val field = buildConfigClass.getDeclaredField(fieldName)
            return field.get(null) as String
        }


        this.apiKey = getBuildConfigValue(context, "INTEMPT_API_KEY")
        this.sourceId = getBuildConfigValue(context, "INTEMPT_SOURCE_ID")
        this.organizationId =getBuildConfigValue(context, "INTEMPT_ORGANIZATION_ID")
        this.projectId = getBuildConfigValue(context, "INTEMPT_PROJECT_ID")
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