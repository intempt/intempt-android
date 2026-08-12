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
        "INTEMPT_E2E_PRODUCT_ID",
        "INTEMPT_E2E_FEED_ID",
        "INTEMPT_E2E_FEED_FIELDS",
    ).associateWith { name ->
        // FEED_FIELDS is the one fixture with a usable default: "id" is present on every
        // feed, so a recommendation call can be made without knowing the feed's shape.
        val default = if (name == "INTEMPT_E2E_FEED_FIELDS") "id" else ""
        cred(name, name, default)
    }

/**
 * Writes the generated config. A typed task with a DirectoryProperty output, because AGP
 * needs a real output property to wire a generated asset directory into a variant.
 *
 * Two earlier attempts failed in ways worth recording. Passing the directory path to
 * `srcDir` told Gradle where the file was but not who produced it, so lint's model task
 * failed dependency validation. Passing the task provider to `srcDir` fixed the dependency
 * and silently produced an APK with no config in it at all — the build went green and the
 * app would have thrown FileNotFoundException at runtime.
 */
abstract class WriteIntemptConfigTask : DefaultTask() {
    @get:Input
    abstract val apiKey: Property<String>

    @get:Input
    abstract val organization: Property<String>

    @get:Input
    abstract val projectSlug: Property<String>

    @get:Input
    abstract val sourceId: Property<String>

    @get:Input
    abstract val apiUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun write() {
        val apiUrlLine = if (apiUrl.get().isBlank()) "" else "\n    \"apiUrl\": \"${apiUrl.get()}\","
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("intempt-config.json").writeText(
            """
            {
              "auth": {
                "INTEMPT_API_KEY": "${apiKey.get()}",
                "INTEMPT_SOURCE_ID": "${sourceId.get()}",
                "INTEMPT_ORGANIZATION_ID": "${organization.get()}",
                "INTEMPT_PROJECT_ID": "${projectSlug.get()}"
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
        minSdk = 23
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

// addGeneratedSourceDirectory is the API that actually works: it registers the directory as a
// variant asset source AND carries the task dependency, so asset merging, lint and the test
// builds all see the file and all wait for it.
androidComponents {
    onVariants { variant ->
        val task =
            tasks.register(
                "write${variant.name.replaceFirstChar { it.uppercase() }}IntemptConfig",
                WriteIntemptConfigTask::class.java,
            ) {
                apiKey.set(intemptApiKey)
                organization.set(intemptOrg)
                projectSlug.set(intemptProject)
                sourceId.set(intemptSourceId)
                apiUrl.set(intemptApiUrl)
            }
        variant.sources.assets?.addGeneratedSourceDirectory(task, WriteIntemptConfigTask::outputDir)
    }
}
