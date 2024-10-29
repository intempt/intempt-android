package com.intempt.core


//@RunWith(RobolectricTestRunner::class)
//@Config(sdk = [34])
//internal class ChangeTrackerUnitTest {
////    @Mock
////    lateinit var configManagerService: ConfigManagerService
//
//    private lateinit var config: ConfigManagerService
//    private lateinit var changeTrackerService: ChangeTrackerService
//    private lateinit var changeTrackerComponent: ChangeTrackerComponent
//    private lateinit var eventSrv: EventPool
//    private lateinit var eventHandlersSpy: EventHandlers
//    private lateinit var context: Context
//    private lateinit var activity: Activity
//    private lateinit var viewGroup: LinearLayout
//    private lateinit var mockResources: Resources
//
//
//
//    @Before
//    fun setUp() {
//        MockitoAnnotations.openMocks(this);
//
//        context = RuntimeEnvironment.getApplication()
//
//        config = spy(ConfigManagerService(context))
//
//        mockResources = mock(Resources::class.java)
//        `when`(mockResources.getResourceEntryName(anyInt())).thenReturn("test_element")
//        `when`(mockResources.getResourceName(anyInt())).thenReturn("com.example.testApp:id/test_element")
//
//
//
//        `when`(config.isTouchEnabled).thenReturn(true)
//
//
//        eventSrv = spy(EventPool(config))
//        changeTrackerService = spy(ChangeTrackerService(eventSrv))
//        changeTrackerComponent = ChangeTrackerComponent(changeTrackerService)
//        eventHandlersSpy = spy(EventHandlers(config))
//
//        activity = Robolectric
//            .buildActivity(Activity::class.java)
//            .create()
//            .start()
//            .resume()
//            .get()
//
//        viewGroup = LinearLayout(activity)
//        activity.setContentView(viewGroup)
//
//
//        ShadowLog.stream = System.out
//    }


//    @Test
//    fun testHandleToggleButtonInteraction() {
//        val element = spy(ToggleButton(activity))
//        testHandleElementInteraction("ToggleButton", element, changeTrackerService::handleCompoundButton) {
//            element.isChecked = true // Simulate interaction
//        }
//
//        // Verify that the correct handler method was called
//        verify(changeTrackerService).handleCompoundButton(element, activity)
//    }
//
//    @Test
//    fun testHandleCheckBoxInteraction() {
//        val element = spy(CheckBox(activity))
//        testHandleElementInteraction("CheckBox", element, changeTrackerService::handleCompoundButton) {
//            element.isChecked = true // Simulate interaction
//        }
//
//        // Verify that the correct handler method was called
//        verify(changeTrackerService).handleCompoundButton(element, activity)
//    }
//
//    @Test
//    fun testHandleRadioButtonInteraction() {
//        val element = spy(RadioButton(activity))
//        testHandleElementInteraction("RadioButton", element, changeTrackerService::handleCompoundButton) {
//            element.isChecked = true // Simulate interaction
//        }
//
//        // Verify that the correct handler method was called
//        verify(changeTrackerService).handleCompoundButton(element, activity)
//    }
//
//    @Test
//    fun testHandleSeekBarInteraction() {
//        val element = spy(SeekBar(activity))
//        testHandleElementInteraction("SeekBar", element) {
//            it.progress = 20
//        }
//
//        verify(changeTrackerService).handleSeekBar(element, activity)
//    }
//
//    @Test
//    fun testHandleEditTextInteraction() {
//        val element = spy(EditText(activity))
//        testHandleElementInteraction("EditText", element, initValue = "initial text") {
//            it.setText("new text")
//        }
//
//        // Verify that the correct handler method was called
//        verify(changeTrackerService).handleEditText(element, activity)
//    }
//


//    @Test
//    fun testHandleSpinnerInteraction() {
//        val element = spy(Spinner(activity))
//
//       `when`(element.resources).thenReturn(mockResources)
//
//        val data = arrayOf("Item 1", "Item 2", "Item 3")
//
//        val adapter = ArrayAdapter(activity, R.layout.simple_spinner_item, data)
//
//        adapter.setDropDownViewResource(R.layout.simple_spinner_dropdown_item)
//        element.adapter = adapter
//
//        val selectedIndex = 1
//        val selectedValue = data[selectedIndex]
//
//        val expectedData = generateExpectedData(
//            elementName = element.javaClass.simpleName,
//            targetText = selectedValue,
//            targetValue = "",
//            targetId = element.resources.getResourceEntryName(element.id),
//            fullTargetId = element.resources.getResourceName(element.id)
//        )
//
//         testHandleElementInteraction(element, expectedData) {
//             it.performClick()
//             it.setSelection(adapter.getPosition(selectedValue))
//             it.setSelection(adapter.getPosition(selectedValue))
//         }
//
//
//        verify(changeTrackerService).handleSpinner(element, activity)
//    }
//
//    @Test
//    fun testHandleDatePickerInteraction() {
//        val element = spy(DatePicker(activity))
//        `when`(element.resources).thenReturn(mockResources)
//
//        val year = 2024
//        val month = 10
//        val dayOfMonth = 28
//
//
//    //TODO: add getter for text
//
//        val expectedData = generateExpectedData(
//            elementName = "DatePicker",
//            targetText = "",
//            targetValue = "$month-$dayOfMonth-$year",
//            targetId = element.resources.getResourceEntryName(element.id),
//            fullTargetId = element.resources.getResourceName(element.id)
//        )
//
//
//        testHandleElementInteraction(element, expectedData) {
//            it.updateDate(year, month, dayOfMonth)
//        }
//
//
//        verify(changeTrackerService).handleDatePicker(element, activity)
//    }
//
//
//    //Negative
//    @Test
//    fun testHandleRatingBarInteractionNegative() {
//        val element = spy(RatingBar(activity))
//        val value = 1.0f;
//        testHandleElementInteractionNegative(element) {
//            it.rating = value
//        }
//    }
//
//    @Test
//    fun testHandleListViewInteractionNegative() {
//        val element = spy(ListView(activity))
//        val data = arrayOf("Item 1", "Item 2", "Item 3")
//        val selectedIndex = 2
//        val adapter = ArrayAdapter(activity, R.layout.simple_list_item_1, data)
//        element.adapter = adapter
//
//
//        testHandleElementInteractionNegative(element) {
//            it.performItemClick(null, selectedIndex, adapter.getItemId(selectedIndex))
//        }
//
//        // Verify that the correct handler method was called
//        verify(changeTrackerService).handleListView(element, activity)
//    }
//
//
//    //Positive
//    @Test
//    fun testHandleRatingBarInteraction() {
//        val element = spy(RatingBar(activity))
//        `when`(element.resources).thenReturn(mockResources)
//        val initialValue = 3.5f;
//
//        val elementName = "RatingBar"
//        val value = 4.5f;
//        val targetValue = "$value"
//        val targetId = element.resources.getResourceEntryName(element.id)
//        val fullTargetId = element.resources.getResourceName(element.id)
//
//        val expectedData = generateExpectedData(
//            elementName,
//            targetText = "",
//            targetValue,
//            targetId,
//            fullTargetId
//        )
//
//        element.rating = initialValue
//        element.resources
//
//        testHandleElementInteraction(element, expectedData) {
//            it.rating = value
//        }
//
//        verify(changeTrackerService).handleRatingBar(element, activity)
//    }
//
//    @Test
//    fun testHandleListViewInteraction() {
//        val element = spy(ListView(activity))
//
//        `when`(element.resources).thenReturn(mockResources)
//
//
//        val data = arrayOf("Item 1", "Item 2", "Item 3");
//        val selectedIndex = 2
//        val adapter = ArrayAdapter(activity, R.layout.simple_list_item_1, data)
//        element.adapter = adapter
//
//
//        Shadows.shadowOf(Looper.getMainLooper()).idle()
//
//        val value = adapter.getItemId(selectedIndex);
//
////TODO: add getter for values and text
//
////        targetText = data[selectedIndex],
////        targetValue = data[selectedIndex],
//        val expectedData = generateExpectedData(
//            elementName = "ListView",
//            targetText = "",
//            targetValue = "",
//            targetId = element.resources.getResourceEntryName(element.id),
//            fullTargetId = element.resources.getResourceName(element.id)
//        )
//
//
//        // Simulate an item click
//        testHandleElementInteraction( element, expectedData ) {
//            it.performItemClick(it, 2, value)
//        }
//
//        // Verify that the correct handler method was called
//        verify(changeTrackerService).handleListView(element, activity)
//    }
//
//
//
//    //Negative
//    private fun <T : View> testHandleElementInteractionNegative(
//        element: T,
//        modifyInteraction: ((T) -> Unit)? = null
//    ) {
//        viewGroup.addView(element)
//
//
//        changeTrackerComponent.onActivityResumed(activity)
//        Shadows.shadowOf(Looper.getMainLooper()).idle()
//
//
//        modifyInteraction?.invoke(element)
//        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
//
//
//        assertNull(eventSrv.lastEvent)
//    }
//
//    //Positive
//    private fun <T : View> testHandleElementInteraction(
//        element: T,
//        expectedData: Map<String, Any>,
//        modifyInteraction: ((T) -> Unit)? = null
//    ) {
//
//
//        viewGroup.addView(element)
//
//        changeTrackerComponent.onActivityResumed(activity)
//        Shadows.shadowOf(Looper.getMainLooper()).idle()
//
//
//        modifyInteraction?.invoke(element)
//        Shadows.shadowOf(Looper.getMainLooper()).runToEndOfTasks()
//
//
//        assertNotNull(eventSrv.lastEvent)
//
//        val eventData = eventSrv.lastEvent
//        val formattedData = eventData?.toFormatted()
//        assertEquals(expectedData, formattedData?.get("data"))
//    }
//
//    private fun generateExpectedData(
//        elementName: String,
//        targetText:  String,
//        targetValue:  String,
//        targetId: String,
//        fullTargetId: String
//    ): Map<String, String>{
//        return mapOf(
//            "targetElement" to elementName,
//            "hierarchy" to "DecorView -> ActionBarOverlayLayout -> FrameLayout -> LinearLayout -> $elementName",
//            "targetText" to targetText,
//            "targetValue" to targetValue,
//            "targetClass" to "android.widget.$elementName",
//            "targetId" to targetId,
//            "fullTargetId" to fullTargetId,
//        )
//    }
//
//
//
//
//}