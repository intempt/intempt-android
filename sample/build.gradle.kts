plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // No baseline here, unlike :app. This module is all new code, so there is nothing to
    // freeze and no reason to accept anything less than clean.
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.intempt.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.intempt.sample"
        // Deliberately the library's minSdk, not higher. If the SDK ever regresses its
        // reach, this module stops assembling and the manifest merger says so by name.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // The point of building release here is R8. The library ships
            // consumer-rules.pro because autocapture dispatches reflectively, and
            // without those rules R8 renames the targets and autocapture silently
            // emits nothing. A minified consumer build is the only thing that proves
            // the rules actually travel with the AAR.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged manifest and the assets, which is how
            // SdkRunsLocallyTest reads the real intempt-config.json.
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(project(":app"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.core.test)
}
