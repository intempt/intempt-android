package com.intempt.intempt_android.eventModels

import android.content.Context
import android.content.res.Configuration
import android.telephony.TelephonyManager
import android.util.Log


import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.*


import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


object LocationInfo {
    var IP: String = "";
    var CITY: String = ""
    var REGION: String = ""
    var COUNTRY: String = ""
}


 class SessionUserAttributes(context: Context) {
    val deviceType: String = getDeviceType(context);
    val carrier: String = getDeviceCarrier(context).toString();
    val platform: String = getDevicePlatform();
    val ipAddress: String = LocationInfo.IP;
    val region: String = LocationInfo.REGION;
    val country: String = LocationInfo.COUNTRY;
    val city: String = LocationInfo.CITY;

    companion object{
         suspend fun getLocationInfo() {
             val startTime = System.currentTimeMillis()
            return withContext(Dispatchers.IO) {
                 val apiUrl = "https://ipapi.co/json/";
                 val client = HttpClient() {
                     install(ContentNegotiation) {
                         json(Json {
                             prettyPrint = true
                             isLenient = true
                         })
                     }
                 }
                 try{
                     val response: HttpResponse = client.get(apiUrl);
                     val locationInfo = response.bodyAsText();

                     val jsonElement = Json.parseToJsonElement(locationInfo).jsonObject

                     LocationInfo.IP = jsonElement["ip"]?.jsonPrimitive?.content ?: "";
                     LocationInfo.REGION = jsonElement["region"]?.jsonPrimitive?.content ?: "";
                     LocationInfo.CITY = jsonElement["city"]?.jsonPrimitive?.content ?: "";
                     LocationInfo.COUNTRY = jsonElement["country_name"]?.jsonPrimitive?.content ?: "";
                 }
                 catch (e: Exception) {
                     e.printStackTrace()
                 }
                 finally {
                     client.close()
                     val endTime = System.currentTimeMillis() // Capture end time
                     val initializationTime = endTime - startTime // Calculate elapsed time
                     Log.d("getLocationInfo Initialization", "Initialization completed in $initializationTime ms")
                 }
             };
        }
    }


    private fun getDeviceType(context: Context): String {
        return when (context.resources.configuration.screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK) {
            Configuration.SCREENLAYOUT_SIZE_SMALL, Configuration.SCREENLAYOUT_SIZE_NORMAL -> "Phone"
            Configuration.SCREENLAYOUT_SIZE_LARGE, Configuration.SCREENLAYOUT_SIZE_XLARGE -> "Tablet"
            else -> "Unknown"
        }
    }

    private fun getDeviceCarrier(context: Context): String? {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return telephonyManager.networkOperatorName
    }

    private fun getDevicePlatform(): String {
        return "Android ${android.os.Build.VERSION.RELEASE}"
    }

}

