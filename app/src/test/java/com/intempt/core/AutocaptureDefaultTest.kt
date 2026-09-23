@file:OptIn(com.intempt.core.internal.InternalIntemptApi::class)

package com.intempt.core

import android.content.Context
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import com.intempt.core.services.ConfigManagerService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
class AutocaptureDefaultTest {
    private fun configFromAsset(options: String): ConfigManagerService {
        val json =
            """
            {
                "auth": {
                    "INTEMPT_API_KEY": "prefix.secret",
                    "INTEMPT_SOURCE_ID": "source",
                    "INTEMPT_ORGANIZATION_ID": "org",
                    "INTEMPT_PROJECT_ID": "project"
                },
                "options": $options
            }
            """.trimIndent()
        val context: Context = spy(ApplicationProvider.getApplicationContext<Context>())
        val assets = mock(AssetManager::class.java)
        `when`(assets.open("intempt-config.json")).thenReturn(ByteArrayInputStream(json.toByteArray()))
        `when`(context.assets).thenReturn(assets)
        return ConfigManagerService(context)
    }

    @Test
    fun `a config asset that omits isAutoCaptureEnabled does not start autocapture at initialize`() {
        assertFalse(configFromAsset("""{ "isLoggingEnabled": false }""").autocaptureEnabledByConfig)
    }

    @Test
    fun `credentials supplied at runtime with no config asset do not start autocapture at initialize`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertFalse(ConfigManagerService(context, null, null).autocaptureEnabledByConfig)
    }

    @Test
    fun `isAutoCaptureEnabled true in the config asset still starts autocapture at initialize`() {
        assertTrue(configFromAsset("""{ "isAutoCaptureEnabled": true }""").autocaptureEnabledByConfig)
    }

    @Test
    fun `isAutoCaptureEnabled false in the config asset does not start autocapture at initialize`() {
        assertFalse(configFromAsset("""{ "isAutoCaptureEnabled": false }""").autocaptureEnabledByConfig)
    }

    @Test
    fun `starting autocapture from code after omitting the key still captures screen views`() {
        assertTrue(configFromAsset("""{ "isLoggingEnabled": false }""").autocaptureOptions.screenViews)
    }

    @Test
    fun `starting autocapture from code with no config asset still captures screen views`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        assertTrue(ConfigManagerService(context, null, null).autocaptureOptions.screenViews)
    }
}
