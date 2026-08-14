package com.intempt.core.services

import android.content.Context
import android.util.Base64
import android.util.Log
import com.intempt.core.autocapture.BaseComponent
import com.intempt.core.types.ConfigKeys
import com.intempt.core.types.ConfigResult
import com.intempt.core.types.Constants
import com.intempt.core.types.DefaultConfigs
import com.intempt.core.types.IntemptConfigs
import com.intempt.core.types.IntemptOptions
import org.json.JSONObject
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class ConfigManagerService
    @Inject
    constructor(
        private val context: Context,
    ) : BaseComponent() {
        // Leading underscores: backing fields for the public read-only accessors below. Suppressed
        // at the declaration rather than left to the ktlint baseline, which pins violations by line
        // number and so breaks on any edit above them — that is exactly what happened here.
        @Suppress("ktlint:standard:property-naming")
        private val _useIpAddressForGeolocation: Boolean

        @Suppress("ktlint:standard:property-naming")
        private val _apiKey: String

        @Suppress("ktlint:standard:property-naming")
        private val _sourceId: String

        @Suppress("ktlint:standard:property-naming")
        private val _organizationId: String

        @Suppress("ktlint:standard:property-naming")
        private val _projectId: String

        var isLoggingEnabled: Boolean
        var isUserOptIn: Boolean
        var isQueueEnabled: Boolean
        val itemsInQueue: Int
        val timeBuffer: Long

        private val _isTouchEnabled: Boolean
        private val _isTextCaptureEnabled: Boolean
        private val _isAutoCaptureEnabled: Boolean

        val sourceId: String get() = _sourceId
        val organization: String get() = _organizationId
        val project: String get() = _projectId
        val isTextCaptureEnabled: Boolean get() = _isTextCaptureEnabled

        /**
         * Whether the platform may geolocate from the request's source IP. Default true, matching
         * Mixpanel's `UseIpAddressForGeolocation`. A privacy-conscious host app sets it false and no
         * geolocation happens at either end.
         */
        val useIpAddressForGeolocation: Boolean get() = _useIpAddressForGeolocation
        val isTouchEnabled: Boolean get() = _isTouchEnabled
        val isAutoCaptureEnabled: Boolean get() = _isAutoCaptureEnabled

        /**
         * The ingestion host. Defaults to production; overridable with `apiUrl` in
         * assets/intempt-config.json so the SDK can be aimed at staging or at a local stub.
         * Previously this was Constants.API_URL, a compile-time constant, which meant the
         * delivery leg could not be exercised without a real project and a real key.
         */
        val apiUrl: String

        /**
         * Optional, opt-in TLS certificate pins ("sha256/BASE64" SPKI hashes, OkHttp
         * CertificatePinner format) for the ingestion endpoint. Empty by default, meaning no
         * pinning and unchanged platform-default TLS trust validation. Set via
         * `certificatePins` in intempt-config.json; consumed by
         * [com.intempt.core.services.CertificatePinning] to build the [javax.net.ssl.SSLSocketFactory]
         * wired into [com.intempt.core.queue.QueueConfig] for delivery requests.
         */
        val certificatePins: List<String>

        val consentUrl: String
            get() = "$apiUrl/v1/${_organizationId}/projects/${_projectId}/consents/data"

        /**
         * The ingestion endpoint, carrying the geolocation flag.
         *
         * `?ip=1` asks the platform to derive geo from the source IP of the request it already
         * receives; `?ip=0` asks it not to. Copied from mixpanel-android, where
         * `MPConfig.getEndPointWithIpTrackingParam` is the entire mechanism — one query parameter,
         * no client-side IP handling and no third party.
         *
         * The SDK previously called ipapi.co per session, read back the device's IP and geo, and put
         * all four in the payload — outside consent gating, with no consumer switch and no
         * sub-processor disclosure. This replaces that.
         *
         * Defaults to on, as Mixpanel's does, so geo keeps working for customers who want it.
         * Turned off with `"useIpAddressForGeolocation": false` in intempt-config.json.
         */
        val eventsUrl: String
            get() =
                "$apiUrl/v1/${_organizationId}/projects/${_projectId}/sources/${_sourceId}/track" +
                    "?ip=" + if (useIpAddressForGeolocation) "1" else "0"

        val optimizationUrl: String
            get() = "$apiUrl/v1/${_organizationId}/projects/${_projectId}/optimization/choose-api"

        val pushNotificationWebhookUrl: String
            get() = "$apiUrl/webhooks/events/push-notification"

        fun recommendationUrl(id: String): String {
            return "$apiUrl/v1/${_organizationId}/projects/${_projectId}/feeds/$id/data"
        }

        init {
            val (configs, options) = getConfigs()

            _apiKey = configs?.apiKey ?: ""
            _sourceId = configs?.sourceId ?: ""
            _organizationId = configs?.organizationId ?: ""
            _projectId = configs?.projectId ?: ""

            _isTouchEnabled = options?.isTouchEnabled ?: DefaultConfigs.IsTouchEnabled.value
            _isTextCaptureEnabled = options?.isTextCaptureEnabled ?: DefaultConfigs.IsTextCaptureEnabled.value
            _isAutoCaptureEnabled = options?.isAutoCaptureEnabled ?: DefaultConfigs.IsAutoCaptureEnabled.value
            _useIpAddressForGeolocation =
                options?.useIpAddressForGeolocation ?: DefaultConfigs.UseIpAddressForGeolocation.value

            itemsInQueue = options?.itemsInQueue ?: DefaultConfigs.ItemsInQueue.value
            timeBuffer = options?.timeBuffer ?: DefaultConfigs.TimeBuffer.value
            isUserOptIn = DefaultConfigs.IsUserOptIn.value
            isLoggingEnabled = options?.isLoggingEnabled ?: DefaultConfigs.IsLoggingEnabled.value
            isQueueEnabled = options?.isQueueEnabled ?: DefaultConfigs.IsQueueEnabled.value
            apiUrl = options?.apiUrl?.takeIf { it.isNotBlank() } ?: Constants.API_URL
            certificatePins = options?.certificatePins ?: DefaultConfigs.CertificatePins.value
        }

        /**
         * True when all four credentials were found in intempt-config.json.
         *
         * A missing or unparseable asset leaves every one of them an empty string — the SDK then
         * runs, queues events, and posts them with no Authorization header, so everything 401s and
         * is dropped. Nothing fails at startup, which is what made a missing config asset look
         * like a healthy SDK. [com.intempt.core.Intempt.initialize] checks this and reports false.
         */
        val isConfigured: Boolean
            get() =
                _apiKey.isNotBlank() &&
                    _sourceId.isNotBlank() &&
                    _organizationId.isNotBlank() &&
                    _projectId.isNotBlank()

        /** Names the credentials that are missing, for a log line a customer can act on. */
        internal fun missingCredentials(): List<String> =
            buildList {
                if (_apiKey.isBlank()) add("apiKey")
                if (_sourceId.isBlank()) add("sourceId")
                if (_organizationId.isBlank()) add("organizationId")
                if (_projectId.isBlank()) add("projectId")
            }

        fun token(): String {
            if (_apiKey.isEmpty()) return ""

            // An API key is "<username>.<password>". A key without a dot used to reach the
            // destructuring below, where split(".") yields one element and the assignment to
            // `password` throws IndexOutOfBoundsException — from inside the auth path, on a
            // typo'd key. Checked rather than caught, so the log line says what is wrong.
            val parts = _apiKey.split(".")
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                Log.e(
                    "Intempt",
                    "The apiKey in intempt-config.json is malformed: it must be \"<id>.<secret>\". " +
                        "No Authorization header can be built, so every event will be rejected.",
                )
                return ""
            }
            val (username, password) = parts
            // android.util.Base64 (API 1), not java.util.Base64 (API 26). This is the auth
            // path — on API 21-25 the java.util version throws and every request fails.
            // NO_WRAP is required: the default inserts newlines, which corrupts an HTTP header.
            return Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
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

                val configs =
                    IntemptConfigs(
                        apiKey = authObject.getString(ConfigKeys.ApiKey.key),
                        sourceId = authObject.getString(ConfigKeys.SourceId.key),
                        organizationId = authObject.getString(ConfigKeys.OrganizationId.key),
                        projectId = authObject.getString(ConfigKeys.ProjectId.key),
                    )

                val options =
                    IntemptOptions(
                        isLoggingEnabled = optionsObject.optBoolean(ConfigKeys.IsLoggingEnabledOpt.key, false),
                        isTouchEnabled = optionsObject.optBoolean(ConfigKeys.IsTouchEnabledOpt.key, true),
                        isTextCaptureEnabled = optionsObject.optBoolean(ConfigKeys.IsTextCaptureEnabled.key, true),
                        isQueueEnabled = optionsObject.optBoolean(ConfigKeys.IsQueueEnabled.key, true),
                        isAutoCaptureEnabled = optionsObject.optBoolean(ConfigKeys.IsAutoCaptureEnabled.key, true),
                        useIpAddressForGeolocation =
                            optionsObject.optBoolean(
                                ConfigKeys.UseIpAddressForGeolocation.key,
                                DefaultConfigs.UseIpAddressForGeolocation.value,
                            ),
                        itemsInQueue = optionsObject.optInt(ConfigKeys.ItemsInQueue.key, 5),
                        timeBuffer = optionsObject.optLong(ConfigKeys.TimeBuffer.key, 5000),
                        apiUrl = optionsObject.optString(ConfigKeys.ApiUrl.key).takeIf { it.isNotBlank() },
                        certificatePins =
                            optionsObject.optJSONArray(ConfigKeys.CertificatePins.key)?.let { pins ->
                                (0 until pins.length()).mapNotNull { index -> pins.optString(index, null) }
                            } ?: emptyList(),
                    )

                ConfigResult(configs, options)
            } catch (e: Exception) {
                ConfigResult(configs = null, options = null)
            }
        }
    }
