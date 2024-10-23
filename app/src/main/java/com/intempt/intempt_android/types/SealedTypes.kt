package com.intempt.intempt_android.types

sealed class AppVisibilityState(val state: String) {
    data object Foreground : AppVisibilityState("Foreground")
    data object Background : AppVisibilityState("Background")

    companion object {
        fun fromString(state: String?): AppVisibilityState {
            return when (state) {
                "Foreground" -> Foreground
                "Background" -> Background
                else -> Background
            }
        }
    }
}

sealed class StorageKeys(val key: String) {
    // App preferences
    data object AppPrefs : StorageKeys("app_prefs")
    data object SessionPrefs : StorageKeys("session_prefs")
    data object UserPrefs : StorageKeys("user_prefs")
    data object FragmentPrefs : StorageKeys("fragment_prefs")

    // Keys for various IDs and timestamps
    data object SessionId : StorageKeys("session_id")
    data object ProfileId : StorageKeys("profile_id")
    data object PageId : StorageKeys("page_id")

    // Timestamp keys
    data object SessionTimestamp : StorageKeys("session_time")
    data object PageTimestamp : StorageKeys("page_time")

    // Version and build type keys
    data object PreviousVersionCode : StorageKeys("previous_version_code")
    data object PreviousBuildType : StorageKeys("previous_build_type")

    // App visibility state key
    data object AppVisibilityState : StorageKeys("app_visibility_state")
}

sealed class IdTypeKeys(val key: String){
    data object SessionId : IdTypeKeys("ses")
    data object ProfileId : IdTypeKeys("prof")
    data object PageId : IdTypeKeys("pag")

}

sealed class ConfigKeys(val key: String){
    data object ApiKey : ConfigKeys("INTEMPT_API_KEY")
    data object SourceId : ConfigKeys("INTEMPT_SOURCE_ID")
    data object OrganizationId : ConfigKeys("INTEMPT_ORGANIZATION_ID")
    data object ProjectId : ConfigKeys("INTEMPT_PROJECT_ID")
    data object Auth : ConfigKeys("auth")
    data object Options : ConfigKeys("options")
    data object IsLoggingEnabledOpt : ConfigKeys("isLoggingEnabled")
    data object IsTouchEnabledOpt : ConfigKeys("isTouchEnabled")
    data object IsTextCaptureEnabled : ConfigKeys("isTextCaptureEnabled")
    data object IsQueueEnabled : ConfigKeys("isQueueEnabled")
    data object IsAutoCaptureEnabled : ConfigKeys("isAutoCaptureEnabled")
    data object ItemsInQueue : ConfigKeys("itemsInQueue")
    data object TimeBuffer : ConfigKeys("timeBuffer")
}