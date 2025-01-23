package com.intempt.core

import android.R
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.AssetManager
import android.content.res.Resources
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerComponent
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerService
import com.intempt.core.autocapture.lifecycleCallbacksTracker.ChangeTrackerService
import com.intempt.core.autocapture.lifecycleCallbacksTracker.LifecycleCallBacksComponent
import com.intempt.core.autocapture.lifecycleCallbacksTracker.LifecycleCallbackService
import com.intempt.core.autocapture.lifecycleCallbacksTracker.ScreenTrackerService
import com.intempt.core.autocapture.lifecycleCallbacksTracker.TouchTrackerService
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.Constants
import com.intempt.core.types.DispatchEventProps
import com.intempt.core.types.StorageKeys
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.mockito.Mockito.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest= Config.NONE
)
class AutoCaptureUnitTest {
    private lateinit var mockResources: Resources
    private lateinit var toggleButton: ToggleButton
    private lateinit var imageView: ImageView
    private lateinit var activity: Activity
    private lateinit var rootView: ViewGroup

    private lateinit var context: Context
    private lateinit var config: ConfigManagerService
    private lateinit var logger: LoggerManagerService
    private lateinit var utils: UtilsService
    private lateinit var httpSrv: HttpManagerService
    private lateinit var intemptEvent: IntemptEventManagerService
    private lateinit var storage: StorageManagerService
    private lateinit var eventPoolSrv: EventPoolManagerService
    private lateinit var changeTrackerService: ChangeTrackerService
    private lateinit var touchTrackerSrv: TouchTrackerService
    private lateinit var screenTrackerService: ScreenTrackerService
    private lateinit var installUpgradeSrv: InstallUpgradeTrackerService
    private lateinit var installUpgradeComponent: InstallUpgradeTrackerComponent
    private lateinit var lifecycleService: LifecycleCallbackService
    private lateinit var lifecycleComponent: LifecycleCallBacksComponent

    private val testScheduler = TestCoroutineScheduler()
    private lateinit var testDispatcher: TestDispatcher
    private val mockAssets: AssetManager = mock(AssetManager::class.java)
    private val jsonConfig = """
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
    private val mockProfId = "prof_test_id_123456"
    private val mockedSesId = "ses_test_id_123456"
    private val mockedPagId = "pag_test_id_123456"


    @OptIn(ExperimentalCoroutinesApi::class)
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        ShadowLog.stream = System.out
        ShadowLog.clear()

         mockResources = mock(Resources::class.java)
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

        Mockito.doNothing().`when`(mockEditor).apply()

        `when`(mockSharedPreferences.edit()).thenReturn(mockEditor)

        activity = spy(Robolectric.buildActivity(Activity::class.java).setup().get())

        Robolectric.flushForegroundThreadScheduler()

        config = spy(ConfigManagerService(context))
        logger = spy(LoggerManagerService(config))
        utils = spy(UtilsService(logger))

        doAnswer { invocation ->
            val action = invocation.getArgument<() -> Unit>(3)
            action()
            null
        }.whenever(utils).debounce(any(), any(), anyOrNull(), any())


        httpSrv = spy(HttpManagerService(config, logger))
        storage = spy(StorageManagerService(context, utils))
        intemptEvent = spy(IntemptEventManagerService(context, storage, utils, config))

        testDispatcher = UnconfinedTestDispatcher(testScheduler)

        eventPoolSrv = spy(
            EventPoolManagerService(
                config,
                logger,
                httpSrv,
                intemptEvent,
                dispatcher = testDispatcher
            )
        )


        installUpgradeSrv = spy(InstallUpgradeTrackerService(
            context,eventPoolSrv, storage, logger, utils
        ))
        installUpgradeComponent = spy(
            InstallUpgradeTrackerComponent(
                srv = installUpgradeSrv,
                dispatcher = testDispatcher
            ),

        )

        touchTrackerSrv = spy(TouchTrackerService(eventPoolSrv, config, utils))
        changeTrackerService = spy(ChangeTrackerService(eventPoolSrv, logger, utils))
        screenTrackerService = spy(ScreenTrackerService(eventPoolSrv, logger, utils, storage))


        testScheduler.advanceUntilIdle()

        Robolectric.flushForegroundThreadScheduler()
    }

    @Test
    fun `should call installUpgrade`() = runTest {
        interceptHttpRequest()
        installUpgradeComponent.start()

        verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `should fail installUpgrade`() = runTest {
        doThrow(RuntimeException("Simulated error during dispatchEvent"))
            .`when`(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

        installUpgradeComponent.start()

        verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

        testScheduler.advanceUntilIdle()

        assertTrue(true)
    }

    @Test
    fun `should call for change event`() = runTest {
        toggleButton = ToggleButton(activity).apply { id = View.generateViewId() }
        rootView = configureRootView(toggleButton)
        activity.setContentView(rootView)
        lifecycleComponent = configLifeCycleComponent(){
            doNothing().`when`(lifecycleService).handleScreenView(any<Activity>())
            doNothing().`when`(lifecycleService).handleScreenLeave(any<Activity>())
            doNothing().`when`(lifecycleService).handleFragmentVisibility(any<Fragment>())
            doNothing().`when`(lifecycleService).handleFragmentAdd(any<Fragment>())
            doNothing().`when`(lifecycleService).handleFragmentRemove(any<Fragment>())
        }
        lifecycleComponent.onActivityResumed(activity)
        Robolectric.flushForegroundThreadScheduler()

        interceptHttpRequest()

        toggleButton.isChecked = !toggleButton.isChecked

        verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `should fail on change event`() = runTest {
        toggleButton = ToggleButton(activity).apply { id = View.generateViewId() }
        rootView = configureRootView(toggleButton)
        activity.setContentView(rootView)
        lifecycleComponent = configLifeCycleComponent(){
            doNothing().`when`(lifecycleService).handleScreenView(any<Activity>())
            doNothing().`when`(lifecycleService).handleScreenLeave(any<Activity>())
            doNothing().`when`(lifecycleService).handleFragmentVisibility(any<Fragment>())
            doNothing().`when`(lifecycleService).handleFragmentAdd(any<Fragment>())
            doNothing().`when`(lifecycleService).handleFragmentRemove(any<Fragment>())
        }
        lifecycleComponent.onActivityResumed(activity)
        Robolectric.flushForegroundThreadScheduler()

        doThrow(RuntimeException("Simulated error during dispatchEvent"))
            .`when`(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

        toggleButton.isChecked = !toggleButton.isChecked

        verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

        testScheduler.advanceUntilIdle()

        assertTrue(true)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
//    @Test
    fun `should fail touch event`() = runTest {
        imageView = ImageView(activity).apply {
            id = View.generateViewId()
            layoutParams = ViewGroup.LayoutParams(200, 200)
            isFocusable = true
            isClickable = true
        }
        rootView = configureRootView(imageView)
        activity.setContentView(rootView)
        Robolectric.flushForegroundThreadScheduler()

        doAnswer { invocation ->
            val action = invocation.getArgument<() -> Unit>(3)  // Get the action (4th argument)
            action()
            null
        }.whenever(utils).debounce(any(), any(), anyOrNull(), any())


        lifecycleComponent = configLifeCycleComponent(){
            doNothing().`when`(lifecycleService).handleScreenView(any<Activity>())
            doNothing().`when`(lifecycleService).handleScreenLeave(any<Activity>())
            doNothing().`when`(lifecycleService).handleFragmentVisibility(any<Fragment>())
            doNothing().`when`(lifecycleService).handleFragmentAdd(any<Fragment>())
            doNothing().`when`(lifecycleService).handleFragmentRemove(any<Fragment>())
            doNothing().`when`(lifecycleService).registerChangeEventListener(any<Activity>())
        }
        testScheduler.advanceUntilIdle()
        lifecycleComponent.onActivityResumed(activity)
        Robolectric.flushForegroundThreadScheduler()

        doThrow(RuntimeException("Simulated error during dispatchEvent"))
            .`when`(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

        val motionEventDown = MotionEvent.obtain(
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            MotionEvent.ACTION_DOWN,
            100f, // Coordinates within the ImageView
            100f,
            0
        )
        activity.dispatchTouchEvent(motionEventDown)

        val motionEventUp = MotionEvent.obtain(
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            MotionEvent.ACTION_UP,
            100f, // Coordinates within the ImageView
            100f,
            0
        )
        activity.dispatchTouchEvent(motionEventUp)
        advanceTimeBy(Constants.DEBOUNCE_DELAY)
        Robolectric.flushForegroundThreadScheduler()

        // Verify that dispatchEvent was called despite the simulated error
        verify(eventPoolSrv).dispatchEvent(any<DispatchEventProps>(), anyString())

        // Ensure all coroutines are completed
        testScheduler.advanceUntilIdle()

        // Assert the test doesn't crash
        assertTrue(true)

    }

    private fun configLifeCycleComponent(block: () -> Unit):LifecycleCallBacksComponent{
        lifecycleService = spy(
            LifecycleCallbackService(
                screenTrackerService,
                touchTrackerSrv,
                changeTrackerService
            )
        )

        block()


        lifecycleComponent = spy(LifecycleCallBacksComponent(lifecycleService))

        testScheduler.advanceUntilIdle()

        return lifecycleComponent
    }

    private fun configureRootView(view:View):ViewGroup{
        val rootView = LinearLayout(activity).apply {
            id = R.id.content
            orientation = LinearLayout.VERTICAL
        }

        rootView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        rootView.measure(
            View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
        )
        rootView.layout(0, 0, rootView.measuredWidth, rootView.measuredHeight)

        doReturn(mockResources).`when`(spy(view)).resources

        rootView.addView(view)

        Robolectric.flushForegroundThreadScheduler()
        rootView.getViewTreeObserver()
        Robolectric.flushForegroundThreadScheduler()

        return rootView
    }

    private fun interceptHttpRequest() = runBlocking {
        doAnswer { invocation ->
            try {
                val url = invocation.getArgument<String>(0)
                val jsonPayload = invocation.getArgument<JSONObject>(1)

                println("URL: $url")

                println("Captured HTTP request payload: $jsonPayload")
                assertNotNull("Captured HTTP request payload:", jsonPayload)
                val type = jsonPayload
                    .getJSONArray("track")
                    .getJSONObject(0)
                    .getString("type")



                println("Captured HTTP request payload type: $type")
            } catch(e: Exception){
                fail("An exception was thrown during the post request: ${e.message}")
            } finally {
                testScheduler.advanceUntilIdle()
            }


            testScheduler.advanceUntilIdle()
        }.whenever(httpSrv).post(any(), any<JSONObject>(), any())
    }

}