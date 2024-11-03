package com.intempt.core

import android.content.Context
import android.content.SharedPreferences
import com.intempt.core.autocapture.installUpgradeTracker.InstallUpgradeTrackerService
import com.intempt.core.intemptCore.DaggerIntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreComponent
import com.intempt.core.intemptCore.IntemptCoreModule
import com.intempt.core.intemptCore.IntemptCoreService
import com.intempt.core.modifications.ModificationComponent
import com.intempt.core.services.ConfigManagerService
import com.intempt.core.services.HttpManagerService
import com.intempt.core.services.IntemptEventManagerService
import com.intempt.core.services.LoggerManagerService
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.services.eventPool.EventPoolManagerService
import com.intempt.core.types.ModificationProvider
import com.intempt.sdk.BuildConfig
import junit.framework.TestCase.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
internal class IntemptCoreUnitTest {
    private lateinit var context: Context

    @Mock
    lateinit var mockModule: IntemptCoreModule

    @Mock
    lateinit var mockComponent: IntemptCoreComponent

    @Mock
    lateinit var mockIntemptCore: IntemptCoreService

    @Mock
    lateinit var mockModification: ModificationComponent

    @Mock
    lateinit var mockExperimentHandler: ModificationProvider

    @Mock
    lateinit var mockPersonalizationHandler:  ModificationProvider


    @Before
    fun setUp() {
//        MockitoAnnotations.openMocks(this)
//
//        context = spy(RuntimeEnvironment.getApplication())
//
//        val mockFactory = spy(DaggerIntemptCoreComponent.factory())
//
//        doAnswer { invocation ->
//            mockComponent
//        }.whenever(mockFactory).create(mockModule)
//
//        whenever(mockComponent.initService()).thenReturn(mockIntemptCore)
//        whenever(mockIntemptCore.modification).thenReturn(mockModification)
//        whenever(mockModification.experimentHandler).thenReturn(mockExperimentHandler)
//        whenever(mockModification.personalizationHandler).thenReturn(mockPersonalizationHandler)


    }

    //@Test
    fun `initialize without error`() {
//        Intempt.initialize(context)
//
//        //verify(mockComponent).inject(Intempt)
//        verify(mockComponent).initService()
//
//        assertEquals(mockExperimentHandler, Intempt.experiment)
//        assertEquals(mockPersonalizationHandler, Intempt.personalization)
    }
}