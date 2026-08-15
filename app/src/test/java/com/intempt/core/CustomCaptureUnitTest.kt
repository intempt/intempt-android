package com.intempt.core

import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.view.View
import android.widget.CheckBox
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
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.customCapture.CustomCaptureService
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.ErrorReporter
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.ConsentAction
import com.intempt.core.types.IntemptValue
import com.intempt.core.types.Product
import com.intempt.core.types.StorageKeys
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
)
class CustomCaptureUnitTest {
    private lateinit var customCaptureSrv: CustomCaptureService
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var context: Context
    private lateinit var storage: StorageManagerService
    private lateinit var errors: ErrorReporter
    private lateinit var component: CustomCaptureComponent
    private lateinit var eventPoolSrv: EventPoolManagerService
    private lateinit var delivery: DeliveryMessages
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var utils: UtilsService

    private val testScheduler = TestCoroutineScheduler()
    private lateinit var testDispatcher: TestDispatcher

    private val mockAssets: AssetManager = mock(AssetManager::class.java)

    private val jsonConfig =
        """
        {
            "auth": {
                "INTEMPT_API_KEY": "1c75007239cf420c929602f74b872906.95084d4f10804fc3b4820dfae579a0a7",
                "INTEMPT_SOURCE_ID": "1430727403234930688",
                "INTEMPT_ORGANIZATION_ID": "intempt_internal_use_only",
                "INTEMPT_PROJECT_ID": "intempt_android"
            },
            "options": {
                "isLoggingEnabled": true,
                "isTouchEnabled": true,
                "isTextCaptureEnabled": true,
                "isQueueEnabled": false,
                "isAutoCaptureEnabled": true,
                "itemsInQueue": 5,
                "timeBuffer": 5000
            }
        }
        """.trimIndent()
    private val mockProfId = "prof_8fde9691-9040-4bbf-bae6-9ca5567f62d0"
    private val mockedSesId = "ses_test_id_123456"
    private val mockedPagId = "pag_test_id_123456"

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()

        context = spy(RuntimeEnvironment.getApplication())

        val inputStream = ByteArrayInputStream(jsonConfig.toByteArray(Charsets.UTF_8))
        `when`(mockAssets.open("intempt-config.json")).thenReturn(inputStream)
        `when`(context.assets).thenReturn(mockAssets)

        val mockSharedPreferences = mock(SharedPreferences::class.java)
        val mockEditor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(mockSharedPreferences)
        `when`(mockEditor.clear()).thenReturn(mockEditor)
        `when`(mockSharedPreferences.getString(eq(StorageKeys.ProfileId.key), anyOrNull())).thenReturn(mockProfId)
        `when`(mockSharedPreferences.getString(eq(StorageKeys.SessionId.key), anyOrNull())).thenReturn(mockedSesId)
        `when`(mockSharedPreferences.getString(eq(StorageKeys.PageId.key), anyOrNull())).thenReturn(mockedPagId)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putInt(anyString(), anyInt())).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)

        doNothing().`when`(mockEditor).apply()

        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)

        config = spy(ConfigManagerService(context))
        logger = spy(LoggerManagerService(config))
        utils = spy(UtilsService(logger))

        storage = spy(StorageManagerService(context, utils))

        doReturn(mockProfId).`when`(storage).getProfileId()

        httpSrv = spy(HttpManagerService(config, logger))
        errors = ErrorReporter(logger)
        customCaptureSrv = CustomCaptureService(storage, logger, errors)

        intemptEvent = spy(IntemptEventManagerService(context, storage, utils, config))
        testDispatcher = UnconfinedTestDispatcher(testScheduler)

        delivery = mock(DeliveryMessages::class.java)

        eventPoolSrv =
            spy(
                EventPoolManagerService(
                    config,
                    logger,
                    httpSrv,
                    intemptEvent,
                    delivery,
                    dispatcher = testDispatcher,
                ),
            )

        component =
            CustomCaptureComponent(
                customCaptureSrv,
                config,
                eventPoolSrv,
                intemptEvent,
                utils,
                storage,
                errors,
            )

        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `should receive event of type identify`() =
        runTest {
            component.identify(
                "test_userID",
                "test_eventTitle",
                IntemptValue.mapOf(mapOf("test" to "test")),
                IntemptValue.mapOf(mapOf("test" to "test")),
            )

            assertEnqueued("identify")
        }

    @Test
    fun `should receive event of type group`() =
        runTest {
            component.group(
                "test_accountID",
                "test_eventTitle",
                IntemptValue.mapOf(mapOf("test" to "test")),
            )

            assertEnqueued("group")
        }

    @Test
    fun `should receive event of type track`() =
        runTest {
            component.track(
                "test_TrackTitle",
                IntemptValue.mapOf(mapOf("test" to "test")),
            )

            assertEnqueued("track")
        }

    @Test
    fun `should receive event of type record`() {
        component.record(
            "test_RecordTitle",
            data = IntemptValue.mapOf(mapOf("test" to "test")),
        )

        assertEnqueued("record")
    }

    @Test
    fun `should receive event of type alias`() {
        component.alias(
            "test_userId",
            "test_anotherUserId",
        )

        assertEnqueued("alias")
    }

    @Test
    fun `should receive event of type consent`() =
        runTest {
            interceptConsentHttpRequest()

            component.consent(
                ConsentAction.REJECT,
                System.currentTimeMillis() + 100000000,
                "test_email",
                "test_message",
                "Test",
            )
        }

    @Test
    fun `should receive event of type productAdd`() =
        runTest {
            interceptConsentHttpRequest()

            component.productAdd(
                "prt1231231231_23",
                1,
            )

            assertEnqueued("product")
        }

    @Test
    fun `should receive event of type productView`() =
        runTest {
            interceptConsentHttpRequest()

            component.productView("productView_id")

            assertEnqueued("product")
        }

    @Test
    fun `should receive event of type productOrdered`() =
        runTest {
            interceptConsentHttpRequest()

            component.productOrdered(
                listOf(
                    Product("prt1231231231_23", 1),
                    Product("sdfgdfgdfg1234234", 45),
                ),
            )

            assertEnqueued("product")
        }

    // ------------------------------------------------- 3.0 contract behaviours

    /**
     * Consent transmits even when the user has opted out.
     *
     * Every capture method returns early on `!isUserOptIn`, and consent used to as well — which
     * meant the one call a user who had objected would make was the one the objection suppressed.
     * A withdrawal has to reach the server, so this path deliberately does not consult the flag.
     */
    @Test
    fun `consent is sent while opted out`() =
        runTest {
            interceptConsentHttpRequest()
            component.optOut()
            assertTrue("precondition: the user is opted out", component.hasOptedOut())

            val accepted = component.consent(ConsentAction.REJECT, System.currentTimeMillis() + 100_000, "e@x.com", null, null)

            testScheduler.advanceUntilIdle()
            assertTrue("an opted-out user must still be able to withdraw consent", accepted)
            verify(httpSrv, atLeastOnce()).post(any(), any<JSONObject>(), any())
        }

    /**
     * The consent decision and the capture flag are one decision, not two.
     *
     * They were independent settings, so an app could record a rejection and keep collecting —
     * the SDK would hold documentary evidence that the user said no while continuing to send.
     */
    @Test
    fun `reject opts out and accept opts back in`() =
        runTest {
            interceptConsentHttpRequest()
            val validUntil = System.currentTimeMillis() + 100_000

            component.optIn()
            component.consent(ConsentAction.REJECT, validUntil)
            testScheduler.advanceUntilIdle()
            assertTrue("a rejection must stop capture", component.hasOptedOut())

            component.consent(ConsentAction.ACCEPT, validUntil)
            testScheduler.advanceUntilIdle()
            assertTrue("an acceptance must resume capture", component.isOptedIn())
        }

    /**
     * Opting out empties the queue rather than only setting a flag.
     *
     * Events captured before the objection would otherwise sit in the durable queue and upload
     * after it: the SDK would stop adding to the pile it was still sending.
     */
    @Test
    fun `opting out discards what is already queued`() {
        component.optIn()
        component.track("Viewed", IntemptValue.mapOf(mapOf("screen" to "home")))
        testScheduler.advanceUntilIdle()

        component.optOut()
        testScheduler.advanceUntilIdle()

        verify(delivery, atLeastOnce()).emptyQueue()
    }

    /** [logOut] keeps the queue; only [reset] discards it. Both rotate the identity. */
    @Test
    fun `logOut keeps the queue and reset empties it`() {
        component.logOut()
        testScheduler.advanceUntilIdle()
        verify(delivery, never()).emptyQueue()

        component.reset()
        testScheduler.advanceUntilIdle()
        verify(delivery, atLeastOnce()).emptyQueue()
    }

    /**
     * logOut runs while opted out.
     *
     * It used to return early on `!isUserOptIn`, leaving the previous user's profileId in place on
     * a shared device — the exact identity inheritance logging out exists to prevent, and most
     * likely to matter for the user who had just objected.
     */
    @Test
    fun `logOut rotates the identity even while opted out`() {
        component.optOut()
        testScheduler.advanceUntilIdle()

        component.logOut()
        testScheduler.advanceUntilIdle()

        verify(storage, atLeastOnce()).clearAllStorage()
    }

    /**
     * A refusal is visible to the caller.
     *
     * These all returned Unit before 3.0, so a rejected call and a working one were
     * indistinguishable at the call site — which is how a silently rejected identify() went
     * unnoticed for weeks.
     */
    @Test
    fun `capture methods report refusal rather than swallowing it`() {
        assertTrue("a valid track must report acceptance", component.track("Viewed", emptyMap()))

        assertTrue("an empty title must be refused", !component.track("", emptyMap()))
        assertTrue("a reserved title must be refused", !component.track("identify", emptyMap()))
        assertTrue("a blank userId must be refused", !component.identify(""))
        assertTrue("an empty order must be refused", !component.productOrdered(emptyList()))
        assertTrue("a zero quantity must be refused", !component.productOrdered(listOf(Product("p1", 0))))

        `when`(config.isUserOptIn).thenReturn(false)
        assertTrue("an opted-out track must report refusal", !component.track("Viewed", emptyMap()))
    }

    /**
     * A non-finite number is refused before it reaches the wire.
     *
     * NaN and Infinity are not JSON. Serialized unchecked the gateway rejects the body — and it
     * rejects the whole batch, so one bad value loses every event queued alongside it.
     */
    @Test
    fun `a non-finite attribute value is refused`() {
        val bad = mapOf("score" to IntemptValue.of(Double.NaN))

        assertTrue(!component.track("Viewed", bad))
        assertTrue(!component.identify("u1", null, bad))
        assertTrue(!component.group("a1", null, bad))
        assertTrue(!component.record("Custom", data = bad))
    }

    @Test
    fun `should clear storage on logout`() {
        val sessionPrefs = context.getSharedPreferences(StorageKeys.SessionPrefs.key, Context.MODE_PRIVATE)
        sessionPrefs.edit().putString("someSessionKey", mockedSesId).apply()

        val appPrefs = context.getSharedPreferences(StorageKeys.AppPrefs.key, Context.MODE_PRIVATE)
        appPrefs.edit().putString("someAppKey", "appData").apply()

        val fragmentPrefs = context.getSharedPreferences(StorageKeys.FragmentPrefs.key, Context.MODE_PRIVATE)
        fragmentPrefs.edit().putString("someFragmentKey", "fragmentData").apply()

        val userPrefs = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
        userPrefs.edit().putString(StorageKeys.ProfileId.key, mockProfId).apply()

        component.logOut()
        testScheduler.advanceUntilIdle()

        assertNull(sessionPrefs.getString("someSessionKey", null))
        assertNull(appPrefs.getString("someAppKey", null))
        assertNull(fragmentPrefs.getString("someFragmentKey", null))

        assertEquals(mockProfId, userPrefs.getString(StorageKeys.ProfileId.key, null))
    }

    @Test
    fun `should add intemptDoNotCapture tag`() {
        val viewsToTest =
            listOf(
                EditText(context),
                Spinner(context),
                ToggleButton(context),
                CheckBox(context),
                RadioButton(context),
                TextView(context),
                SeekBar(context),
                RatingBar(context),
                TimePicker(context),
                DatePicker(context),
                ListView(context),
            )

        val unsupportedView = View(context)

        viewsToTest.forEach { view ->
            component.doNotCaptureText(view)
            // Check that the tag was set
            assertEquals(true, view.getTag(R.id.intemptDoNotCapture))
        }

        component.doNotCaptureText(unsupportedView)

        assertNull(unsupportedView.getTag(R.id.intemptDoNotCapture))

        val messageCaptor = argumentCaptor<String>()
        verify(logger, atLeastOnce()).error(messageCaptor.capture())

        // Verify that the specific error message is present in the captured logs
        assertTrue(
            messageCaptor.allValues.any {
                it.trim() ==
                    (
                        "Can't accept view of type ${unsupportedView.javaClass.name}. " +
                            "Supported types are: EditText, Spinner, ToggleButton, CheckBox, " +
                            "RadioButton, CompoundButton, TextView, SeekBar, RatingBar, " +
                            "TimePicker, DatePicker, ListView."
                    ).trim()
            },
        )
    }

    @Test
    fun `on tracking blocked`() {
        `when`(config.isUserOptIn).thenReturn(false)

        component.identify("test", "test", IntemptValue.mapOf(mapOf("test" to "test")))
        component.group("test", "test", IntemptValue.mapOf(mapOf("test" to "test")))
        component.track("test", IntemptValue.mapOf(mapOf("test" to "test")))
        component.record("test", "test")
        component.alias("test", "test")
        component.logOut()

        testScheduler.advanceUntilIdle()

        // Was: eventPoolSrv.eventsList.lastOrNull() == null, reading an in-memory list
        // that no longer exists. Asserting on the collaborator is a stronger statement of
        // the same contract — an opted-out SDK must not hand anything to the queue at all.
        verify(delivery, never()).enqueueEvent(any())
    }

    @Test
    fun `should change isUserOptIn value`() {
        component.optIn()
        assertTrue(config.isUserOptIn)

        component.optOut()
        assertTrue(!config.isUserOptIn)
    }

    @Test
    fun `should change isLoggingEnabled value`() {
        component.enableLogging()
        assertTrue(config.isLoggingEnabled)

        component.disableLogging()
        assertTrue(!config.isLoggingEnabled)
    }

//    @Test
    fun `should call recommendation API`() =
        runTest {
            val id = "246"
            val quantity = 3
            val fields = listOf("id", "price")
            val productId = "26701"

            val res =
                component.products(
                    feedId = id,
                    count = quantity,
                    fields = fields,
                    productId = productId,
                )

            assertNotNull(res)
        }

    /**
     * Asserts that the event actually reached the delivery queue.
     *
     * The previous version of this helper stubbed `httpSrv.post` and asserted INSIDE the
     * `doAnswer`. Production stopped posting on the tracking path when the durable queue
     * landed — `EventPoolManagerService.startEventCollection` routes everything except consent
     * to `delivery.enqueueEvent` — so `post` was never called, the answer body never ran, and
     * nine tests were green regardless of the code. Deleting `emitEvent` from all nine
     * production methods left every one of them passing.
     *
     * `verify` outside the action is the difference: a call that never happens now fails.
     */
    private fun assertEnqueued(expectedEventType: String) {
        val captor = ArgumentCaptor.forClass(JSONObject::class.java)
        verify(delivery, atLeastOnce()).enqueueEvent(captor.capture())
        val types = captor.allValues.map { it.getString("type") }
        assertTrue(
            "expected an enqueued event of type '$expectedEventType', got $types",
            types.contains(expectedEventType),
        )
    }

    private fun interceptConsentHttpRequest() =
        runBlocking {
            doAnswer { invocation ->
                try {
                    val url = invocation.getArgument<String>(0)
                    val jsonPayload = invocation.getArgument<JSONObject>(1)

                    println("URL: $url")
                    println("Captured HTTP request payload: $jsonPayload")
                    assertNotNull("Captured HTTP request payload:", jsonPayload)
                } catch (e: Exception) {
                    e.printStackTrace()
                    fail("An exception was thrown during the post request: ${e.message}")
                } finally {
                    testScheduler.advanceUntilIdle()
                }
            }.whenever(httpSrv).post(any(), any<JSONObject>(), any())
        }
}
