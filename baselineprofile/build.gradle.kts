// Generates a Baseline Profile for :sample's cold start with the SDK initialized. This is a
// `com.android.test` module (Macrobenchmark), not part of the shipped SDK — its only output is
// a Human Readable Rules .txt file that gets hand-copied into app/src/main/baselineProfiles
// (see that directory's notes; a library module does not consume the androidx.baselineprofile
// "consumer" plugin the way an application does).
plugins {
    // No version here: androidx.baselineprofile brings com.android.test onto the classpath
    // itself, and specifying a version on top of that causes a plugin resolution conflict.
    id("com.android.test")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.baseline.profile)
}

android {
    namespace = "com.intempt.baselineprofile"
    compileSdk = 35

    defaultConfig {
        minSdk = 28 // Macrobenchmark's floor: ART iorap/profile APIs it depends on need API 28+.
        targetSdk = 35

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // This module only ever runs on CI/dev emulators (see the CI job's comment on why
        // absolute numbers here are informational, not a device-accurate SLA); without this,
        // MacrobenchmarkRule hard-fails every run with "ERRORS (not suppressed): EMULATOR"
        // before it measures anything.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    // This module measures :sample, so it must build against the same target it profiles.
    targetProjectPath = ":sample"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// Required by the com.android.test plugin: it needs to know which variant of the target
// app/module to instrument.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.enable = true
    }
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

baselineProfile {
    // No Gradle Managed Device configured for this environment; drive whatever real device or
    // emulator is already connected via adb (the manually booted Pixel_10_Pro_XL AVD).
    useConnectedDevices = true
}
