// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    // Declared, not applied. The :sample module applies it only when a real
    // google-services.json is present — see the note there. Version matches the one
    // documented in README.md's push setup, so following the README reproduces this build.
    id("com.google.gms.google-services") version "4.4.2" apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.android.library) apply false
}

