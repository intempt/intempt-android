package com.intempt.core

import android.R
import android.app.Activity
import android.content.Context
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.DatePicker
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TimePicker
import android.widget.ToggleButton
import androidx.compose.ui.platform.ComposeView
import androidx.fragment.app.Fragment
import com.intempt.core.autocapture.lifecycleCallbacksTracker.ChangeTrackerService
import com.intempt.core.autocapture.lifecycleCallbacksTracker.LifecycleCallBacksComponent
import com.intempt.core.autocapture.lifecycleCallbacksTracker.LifecycleCallbackService
import com.intempt.core.autocapture.lifecycleCallbacksTracker.ScreenTrackerService
import com.intempt.core.autocapture.lifecycleCallbacksTracker.TouchTrackerService
import com.intempt.core.eventModels.UiElementEvent
import com.intempt.core.queue.DeliveryMessages
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.DispatchEventProps
import junit.framework.TestCase.assertNotNull
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
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream

// Deliberately the platform EditText, not AppCompatEditText: this is a test double for the
// class autocapture branches on, and it only exists to return a mock ViewTreeObserver. The
// lint check is about theming in real UI, which does not apply to a headless test view.
// Suppressed here rather than disabled module-wide, so NewApi and the rest stay enforced on
// test sources — enabling that check is what surfaced this, and it also caught a real
// API-24 call in the instrumented suite.
@android.annotation.SuppressLint("AppCompatCustomView")
class CustomEditText(context: Activity, private val mockObserver: ViewTreeObserver) : EditText(context) {
    override fun getViewTreeObserver(): ViewTreeObserver {
        return mockObserver
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
)
class ChangeTrackerUnitTest {
    private lateinit var rootView: ViewGroup
    private lateinit var toggleButton: ToggleButton
    private lateinit var checkBox: CheckBox
    private lateinit var radioButton: RadioButton
    private lateinit var seekBar: SeekBar
    private lateinit var spinner: Spinner
    private lateinit var editText: EditText
    private lateinit var datePicker: DatePicker
    private lateinit var ratingBar: RatingBar
    private lateinit var timePicker: TimePicker
    private lateinit var listView: ListView
    private lateinit var composeView: ComposeView

    private lateinit var context: Context
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var utils: UtilsService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var storage: StorageManagerService

    private lateinit var activity: Activity
    private lateinit var eventPoolSrv: EventPoolManagerService
    private lateinit var changeTrackerService: ChangeTrackerService
    private lateinit var touchTrackerSrv: TouchTrackerService
    private lateinit var screenTrackerService: ScreenTrackerService
    private lateinit var lifecycleService: LifecycleCallbackService
    private lateinit var lifecycleComponent: LifecycleCallBacksComponent

    private val testScheduler = TestCoroutineScheduler()
    private lateinit var testDispatcher: TestDispatcher

    private val mockAssets: AssetManager = mock(AssetManager::class.java)

    private val jsonConfig =
        """
        {
            "auth": {
                "INTEMPT_API_KEY": "9643576a2cfa47729a1eb63213074e78.1a4f98ffc8f648d3a4c8455a2041cae5",
                "INTEMPT_SOURCE_ID": "687499928542224384",
                "INTEMPT_ORGANIZATION_ID": "intempt2",
                "INTEMPT_PROJECT_ID": "intempt2_project"
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

    private val mockedPayload =
        arrayOf(
            UiElementEvent(
                eventId = "mockEventId",
                sessionId = "mockSessionId",
                pageId = "mockPageId",
                profileId = "mockProfileId",
                targetElement = "mockTargetElement",
                hierarchy = "mockHierarchy",
                targetText = "mockTargetText",
                targetValue = "mockTargetValue",
                targetClass = "mockTargetClass",
                targetId = "mockTargetId",
                fullTargetId = "mockFullTargetId",
            ),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()

        val mockResources = mock(Resources::class.java)
        `when`(mockResources.getResourceEntryName(anyInt())).thenAnswer { invocation ->
            val resourceId = invocation.arguments[0] as Int
            "mocked_resource_entry_name_$resourceId"
        }
        `when`(mockResources.getResourceName(anyInt())).thenAnswer { invocation ->
            val resourceId = invocation.arguments[0] as Int
            "mocked_resource_name_$resourceId"
        }

        context = spy(RuntimeEnvironment.getApplication())

        val inputStream = ByteArrayInputStream(jsonConfig.toByteArray(Charsets.UTF_8))

        `when`(mockAssets.open("intempt-config.json")).thenReturn(inputStream)

        `when`(context.assets).thenReturn(mockAssets)

        activity = spy(Robolectric.buildActivity(Activity::class.java).setup().get())

        rootView = createUIComponentsForTesting(activity, mockResources)

        Robolectric.flushForegroundThreadScheduler()
        rootView.getViewTreeObserver()
        Robolectric.flushForegroundThreadScheduler()
        activity.setContentView(rootView)

        Robolectric.flushForegroundThreadScheduler()

        config = spy(ConfigManagerService(context))
        logger = spy(LoggerManagerService(config))
        utils = spy(UtilsService(logger))

        doAnswer { invocation ->
            val action = invocation.getArgument<() -> Unit>(3) // Get the action (4th argument)
            action()
            null
        }.whenever(utils).debounce(any(), any(), anyOrNull(), any())

        httpSrv = spy(HttpManagerService(config, logger))
        storage = spy(StorageManagerService(context, utils))
        intemptEvent = spy(IntemptEventManagerService(context, storage, utils, config))

        doReturn(mockedPayload).`when`(intemptEvent).generateUiElementEventPayload(any())

        testDispatcher = UnconfinedTestDispatcher(testScheduler)

        eventPoolSrv =
            spy(
                EventPoolManagerService(
                    config,
                    logger,
                    httpSrv,
                    intemptEvent,
                    mock(DeliveryMessages::class.java),
                    dispatcher = testDispatcher,
                ),
            )

        touchTrackerSrv = spy(TouchTrackerService(eventPoolSrv, config, utils))
        changeTrackerService = spy(ChangeTrackerService(eventPoolSrv, logger, utils))
        screenTrackerService = spy(ScreenTrackerService(eventPoolSrv, logger, utils, storage))

        lifecycleService =
            spy(
                LifecycleCallbackService(
                    screenTrackerService,
                    touchTrackerSrv,
                    changeTrackerService,
                ),
            )

        doNothing().`when`(lifecycleService).handleScreenView(any<Activity>())
        doNothing().`when`(lifecycleService).handleScreenLeave(any<Activity>())
        doNothing().`when`(lifecycleService).handleFragmentVisibility(any<Fragment>())
        doNothing().`when`(lifecycleService).handleFragmentAdd(any<Fragment>())
        doNothing().`when`(lifecycleService).handleFragmentRemove(any<Fragment>())

        lifecycleComponent = spy(LifecycleCallBacksComponent(lifecycleService))

        testScheduler.advanceUntilIdle()

        lifecycleComponent.onActivityResumed(activity)

        Robolectric.flushForegroundThreadScheduler()
    }

    @Test
    fun `should interact with Toggle button`() =
        runTest {
            interceptHttpRequest()

            toggleButton.isChecked = !toggleButton.isChecked

            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `should interact with CheckBox button`() =
        runTest {
            interceptHttpRequest()

            checkBox.isChecked = true

            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `should interact with RadioButton`() =
        runTest {
            interceptHttpRequest()
            radioButton.isChecked = true

            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `should interact with EditText`() =
        runTest {
            interceptHttpRequest()

            editText.setText("New Text")

            Shadows.shadowOf(Looper.getMainLooper()).idle()
            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `should interact with SeekBar`() =
        runTest {
            interceptHttpRequest()

            seekBar.progress = 50

            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())
            testScheduler.advanceUntilIdle()
        }

    // @Test
    fun `should interact with DatePicker`() =
        runTest {
            interceptHttpRequest()
            datePicker.updateDate(2023, 11, 5)
            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())
            testScheduler.advanceUntilIdle()
        }

    @Test
    fun `should interact with RatingBar`() =
        runTest {
            interceptHttpRequest()
            ratingBar.rating = 4.5f
            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())
            testScheduler.advanceUntilIdle()
        }

//    @Test
    fun `should interact with TimePicker`() =
        runTest {
            interceptHttpRequest()
            timePicker.hour = 12
            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())
            testScheduler.advanceUntilIdle()
        }

    // @Test
    fun `should interact with Spinner`() =
        runTest {
            interceptHttpRequest()
            spinner.setSelection(1)
            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())
            testScheduler.advanceUntilIdle()
        }

    // @Test
    fun `should interact with ListView`() =
        runTest {
            interceptHttpRequest()

//        listView.performItemClick(
//            listView.adapter.getView(0, null, listView),
//            0,
//            listView.adapter.getItemId(0)
//        )

            listView.setSelection(1)
            verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())
            testScheduler.advanceUntilIdle()
        }

    private fun createUIComponentsForTesting(
        activity: Activity,
        mockResources: Resources,
    ): ViewGroup {
        val rootView =
            LinearLayout(activity).apply {
                id = R.id.content
                orientation = LinearLayout.VERTICAL
            }
        rootView.layoutParams =
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )

        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        )
        rootView.layout(0, 0, rootView.measuredWidth, rootView.measuredHeight)

        checkBox = CheckBox(activity).apply { id = View.generateViewId() }
        toggleButton = ToggleButton(activity).apply { id = View.generateViewId() }
        radioButton = RadioButton(activity).apply { id = View.generateViewId() }

        seekBar = SeekBar(activity).apply { id = View.generateViewId() }
        editText = EditText(activity).apply { id = View.generateViewId() }
        datePicker =
            DatePicker(activity).apply {
                id = View.generateViewId()
            }
        ratingBar = RatingBar(activity).apply { id = View.generateViewId() }
        timePicker = TimePicker(activity).apply { id = View.generateViewId() }

        listView = ListView(activity).apply { id = View.generateViewId() }
        val listViewAdapter = ArrayAdapter(activity, R.layout.simple_list_item_1, listOf("Item 1", "Item 2", "Item 3"))
        listView.adapter = listViewAdapter

        spinner = Spinner(activity).apply { id = View.generateViewId() }
        val spinnerAdapter = ArrayAdapter(activity, R.layout.simple_spinner_item, listOf("Item 1", "Item 2"))
        spinnerAdapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
        spinner.adapter = spinnerAdapter

        composeView = spy(ComposeView(activity).apply { id = View.generateViewId() })

        listOf(
            toggleButton,
            checkBox,
            radioButton,
            editText,
            seekBar,
            datePicker,
            ratingBar,
            timePicker,
            listView,
            spinner,
        ).forEach { view ->
            doReturn(mockResources).`when`(spy(view)).resources

            rootView.addView(view)
            Robolectric.flushForegroundThreadScheduler()
        }

        return rootView
    }

    private fun interceptHttpRequest() =
        runBlocking {
            doAnswer { invocation ->
                try {
                    val url = invocation.getArgument<String>(0)
                    val jsonPayload = invocation.getArgument<JSONObject>(1)

                    println("URL: $url")

                    println("Captured HTTP request payload: $jsonPayload")
                    assertNotNull("Captured HTTP request payload:", jsonPayload)
                    val type =
                        jsonPayload
                            .getJSONArray("track")
                            .getJSONObject(0)
                            .getString("type")

                    println("Captured HTTP request payload type: $type")
                } catch (e: Exception) {
                    fail("An exception was thrown during the post request: ${e.message}")
                } finally {
                    testScheduler.advanceUntilIdle()
                }

                testScheduler.advanceUntilIdle()
            }.whenever(httpSrv).post(any(), any<JSONObject>(), any())
        }
}
