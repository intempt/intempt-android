package com.intempt.core.modifications

import com.intempt.core.types.ModificationProvider
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.types.ModificationBodyParam
import com.intempt.core.types.ModificationGetParam
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.future.future
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ModificationsService @Inject constructor(
    private val storage: StorageManagerService,
    private val config: ConfigManagerService,
    private val logger: LoggerManagerService,
    private val http: HttpManagerService,
    private val utils: UtilsService
): BaseComponent(logger) {

    fun modificationFactory(optimizationType: String): ModificationProvider = object:
        ModificationProvider {
        private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            override suspend fun getByGroup(data: List<String>): JsonElement? {
                return getModification(ModificationGetParam(optimizationType, data, isNameType = false))
            }

            override suspend fun getByName(data: List<String>): JsonElement? {
                return getModification(ModificationGetParam(optimizationType, data, isNameType = true))
            }

            override fun getByGroupAsync(data: List<String>): CompletableFuture<JsonElement?> {
                return coroutineScope.future { getByGroup(data) }
            }

            override fun getByNameAsync(data: List<String>): CompletableFuture<JsonElement?> {
                return coroutineScope.future { getByName(data) }
            }
    }

    private suspend fun getModification(params: ModificationGetParam): JsonElement? {
        val (optimizationType, data, isNameType) = params
        val paramType = if (isNameType) "name" else "group"


        val body = generateBody(
            ModificationBodyParam(optimizationType, data, paramType)
        )
        return request(body)
    }

    private fun generateBody(params: ModificationBodyParam): JSONObject? {
        val (optimizationType, data, paramType) = params;
         val profileId: String = storage.getProfileId();
         val sourceId: String = config.sourceId;
         val deviceType = "mobile";

       return utils.withTryCatch("Error generating request body"){
            JSONObject().apply {
               put("identification", JSONObject().apply {
                   put("profileId", profileId)
                   put("sourceId", sourceId)
               })
               put(paramType, data)
               put("optimizationType", optimizationType)
               put("device", deviceType)
           }
       }.also { result ->
           logger.log("request body: $result")
       }
    }

    private suspend fun request(body:JSONObject?): JsonElement? {
       if(body === null) return null;
       val url = config.optimizationUrl;

       return utils.withTryCatchSuspend("Error in request"){
           http.post(url, body)?.bodyAsText().let {
               val jsonResponse = it?.let { it1 -> Json.parseToJsonElement(it1).jsonObject }
               logger.log("POST | Response: $jsonResponse")
               jsonResponse
           }
       }
   }
}