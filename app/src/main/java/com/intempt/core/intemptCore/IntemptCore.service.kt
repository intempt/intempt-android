package com.intempt.core.intemptCore

import com.intempt.core.autocapture.AutoCaptureComponent
import com.intempt.core.customCapture.CustomCaptureComponent
import com.intempt.core.modifications.ModificationComponent
import com.intempt.core.services.StorageManagerService
import com.intempt.core.services.UtilsService
import com.intempt.core.types.IdTypeKeys
import com.intempt.core.types.StorageKeys
import com.intempt.sdk.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class IntemptCoreService  @Inject constructor(
    private val storage: StorageManagerService,
    private val utils: UtilsService,
    private val autoCaptureComponent: AutoCaptureComponent,
    private val customCaptureComponent: CustomCaptureComponent,
    private val modificationComponent: ModificationComponent,
) {
    internal val capture: CustomCaptureComponent
        get() = customCaptureComponent

    internal val modification : ModificationComponent
        get() = modificationComponent

    private fun setProfileId() {
        val profileId = storage.getProfileId()

        if (profileId.isEmpty()) {
            storage.setStorageItem(
                prefs = StorageKeys.UserPrefs.key,
                key = StorageKeys.ProfileId.key,
                value = utils.generateId(IdTypeKeys.ProfileId.key),
            ) { key, value ->
                putString(key, value)
            }
            autoCaptureComponent.logger.log("Set profile Id")
        }
    }



    init{
        setProfileId()
        autoCaptureComponent.start()
        autoCaptureComponent.logger.log("Intempt SDK initialized")
        autoCaptureComponent.logger.log("VERSION: ${BuildConfig.sdkVersion}")
    }











}