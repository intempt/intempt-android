package com.intempt.core.services

import android.content.Context
import com.intempt.core.types.StorageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class StorageManagerServiceTest {
    private lateinit var context: Context
    private lateinit var utils: UtilsService
    private lateinit var storage: StorageManagerService

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        utils = mock(UtilsService::class.java)
        var counter = 0
        `when`(utils.generateId(anyString())).thenAnswer { "prof_fresh_${counter++}" }
        storage = StorageManagerService(context, utils)
    }

    /**
     * reset()/logOut() promise that the very NEXT getProfileId() sees the rotated
     * identity. The pre-3.0.3 implementation ran the wipe-and-remint on a
     * fire-and-forget coroutine, so a caller reading immediately after reset()
     * raced it and still saw the old profile id — observed end-to-end from the
     * React Native bridge, where reset() resolved and getProfileId() returned
     * the previous user's id. No scheduler-advancing here, on purpose: the
     * rotation must be visible without yielding.
     */
    @Test
    fun `clearAllStorage rotates the profileId synchronously`() {
        storage.setLocalProp(StorageKeys.ProfileId.key, "prof_old")
        assertEquals("prof_old", storage.getProfileId())

        storage.clearAllStorage()

        val rotated = storage.getProfileId()
        assertNotEquals("the old identity must not survive a reset", "prof_old", rotated)
        assertTrue("a fresh id must already be minted, not left blank", rotated.startsWith("prof_fresh_"))
    }

    @Test
    fun `clearAllStorage persists the fresh profileId, not just caches it`() {
        storage.clearAllStorage()
        val minted = storage.getProfileId()

        // A second service over the same prefs must see the same identity —
        // i.e. the rotation reached SharedPreferences, not only localStore.
        val prefs = context.getSharedPreferences(StorageKeys.UserPrefs.key, Context.MODE_PRIVATE)
        assertEquals(minted, prefs.getString(StorageKeys.ProfileId.key, null))
    }
}
