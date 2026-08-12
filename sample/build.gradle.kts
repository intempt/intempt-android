import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    // No baseline here, unlike :app. This module is all new code, so there is nothing to
    // freeze and no reason to accept anything less than clean.
    alias(libs.plugins.ktlint)
}

// intempt-config.json is generated rather than committed, because the e2e suite runs against
// the real ingestion endpoint and this repository is public. Real values come from a
// gitignored local.properties or from the environment (CI secrets); when neither is present
// the generated file carries placeholders so the build and the JVM tests still work.
//
// Committing a live ingestion key to a public repo is the one thing this must never do.
val credProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun cred(
    prop: String,
    env: String,
    fallback: String,
): String = (credProps.getProperty(prop) ?: System.getenv(env))?.takeIf { it.isNotBlank() } ?: fallback

val intemptApiKey = cred("intempt.apiKey", "INTEMPT_API_KEY", "sample-key-id.sample-key-secret")
val intemptOrg = cred("intempt.organization", "INTEMPT_ORGANIZATION_ID", "sample-org")
val intemptProject = cred("intempt.project", "INTEMPT_PROJECT_ID", "sample-project")
val intemptSourceId = cred("intempt.sourceId", "INTEMPT_SOURCE_ID", "sample-source")
val intemptApiUrl = cred("intempt.apiUrl", "INTEMPT_API_URL", "")

/**
 * Test fixtures for the e2e suite, looked up under one name in both places: a key in the
 * gitignored local.properties, or an environment variable in CI. Blank means "not supplied",
 * and the tests that need it skip rather than fail — a red suite on a missing fixture trains
 * people to ignore it, and a fabricated id gives a green run that proves nothing.
 *
 * These are deliberately not part of intempt-config.json. They are inputs to the tests, not
 * configuration the SDK reads.
 */
val e2eFixtures =
    listOf(
        "INTEMPT_E2E_USER_ID",
        "INTEMPT_E2E_ACCOUNT_ID",
        "INTEMPT_E2E_PRODUCT_ID",
        "INTEMPT_E2E_FEED_ID",
        "INTEMPT_E2E_FEED_FIELDS",
        "INTEMPT_E2E_EXPERIMENT_NAME",
        "INTEMPT_E2E_EXPERIMENT_GROUP",
        "INTEMPT_E2E_PERSONALIZATION_NAME",
        "INTEMPT_E2E_PERSONALIZATION_GROUP",
    ).associateWith { name ->
        // FEED_FIELDS is the one fixture with a usable default: "id" is present on every
        // feed, so a recommendation call can be made without knowing the feed's shape.
        val default = if (name == "INTEMPT_E2E_FEED_FIELDS") "id" else ""
        cred(name, name, default)
    }

val generatedAssetsDir = layout.buildDirectory.dir("generated/intemptConfig/assets")

val writeIntemptConfig =
    tasks.register("writeIntemptConfig") {
        description = "Generates sample/assets/intempt-config.json from local.properties or the environment."
        val outDir = generatedAssetsDir
        val apiKey = intemptApiKey
        val org = intemptOrg
        val project = intemptProject
        val sourceId = intemptSourceId
        val apiUrl = intemptApiUrl
        // Declared as inputs so a credential change regenerates the asset rather than
        // silently reusing a stale one from a previous build.
        inputs.property("apiKey", apiKey)
        inputs.property("org", org)
        inputs.property("project", project)
        inputs.property("sourceId", sourceId)
        inputs.property("apiUrl", apiUrl)
        outputs.dir(outDir)
        doLast {
            val apiUrlLine = if (apiUrl.isBlank()) "" else "\n    \"apiUrl\": \"$apiUrl\","
            val dir = outDir.get().asFile
            dir.mkdirs()
            dir.resolve("intempt-config.json").writeText(
                """
                {
                  "auth": {
                    "INTEMPT_API_KEY": "$apiKey",
                    "INTEMPT_SOURCE_ID": "$sourceId",
                    "INTEMPT_ORGANIZATION_ID": "$org",
                    "INTEMPT_PROJECT_ID": "$project"
                  },
                  "options": {$apiUrlLine
                    "isLoggingEnabled": true,
                    "isAutoCaptureEnabled": true,
                    "isTouchEnabled": true,
                    "isTextCaptureEnabled": true,
                    "isQueueEnabled": true,
                    "itemsInQueue": 5,
                    "timeBuffer": 5000
                  }
                }
                """.trimIndent() + "\n",
            )
        }
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Surfaced to the instrumented suite as BuildConfig constants.
        e2eFixtures.forEach { (name, value) ->
            buildConfigField("String", name, "\"$value\"")
        }
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

    buildFeatures {
        buildConfig = true
    }

    sourceSets["main"].assets.srcDir(generatedAssetsDir)

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

    // The suite that actually matters. Everything found on a device this week — the API 24
    // startup crash, the password reaching the wire, identify()/group() dropping the obvious
    // call — was found by hand with adb and sqlite3, which gates nothing. These run the same
    // checks on a real emulator in CI.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner.test)
    androidTestImplementation(libs.androidx.rules.test)
    androidTestImplementation(libs.androidx.core.test)
    // recommendation() is a suspend function, so the suite needs runBlocking. :app depends on
    // coroutines with `implementation`, which does not reach a consumer's compile classpath.
    androidTestImplementation(libs.kotlinx.coroutines.android)
}

// Every variant's asset merge depends on the generated config.
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
    dependsOn(writeIntemptConfig)
}
