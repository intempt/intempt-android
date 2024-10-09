package com.intempt.intempt_android.sessiontracker

import android.content.Context
import android.content.res.Configuration
import android.telephony.TelephonyManager
import android.util.Log


import io.ktor.client.*

import io.ktor.client.request.*
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.*

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.*


@Serializable
data class LocationInfo(
    val ip: String,
    val city: String,
    val region: String,
    val country: String
)


open class SessionUserAttributes(context: Context) {

   protected val deviceType: String = getDeviceType(context);
   protected val carrier: String = getDeviceCarrier(context).toString();
   protected val platform: String = getDevicePlatform();
//   protected val ipAddress: String = "0.0.0.0";
//   protected val region: String;
//   protected val country: String;
//   protected val city: String;

    companion object{
        suspend fun getLocationInfo(): String? {
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
                    return@withContext locationInfo;

                }
                catch (e: Exception) {
                    e.printStackTrace()
                }
                finally {
                    client.close()
                }
                return@withContext null
            }
        }
    }

   init {
        val job = Job()
        val coroutineScope = CoroutineScope(Dispatchers.Main + job)

        coroutineScope.launch {
           val locationInfo =  getLocationInfo();

           Log.d("LocationInfo", locationInfo.toString())


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

    private suspend fun getLocationInfo(): String? {
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
                return@withContext locationInfo;

            }
            catch (e: Exception) {
                e.printStackTrace()
            }
            finally {
                client.close()
            }
            return@withContext null
        }
    }

}

