import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.deployer)
    alias(libs.plugins.gradleup.nmcp)
    id("kotlin-kapt")
    id("maven-publish")
    id("kotlin-parcelize")
    id("jacoco")
    alias(libs.plugins.ktlint)
}

jacoco {
    toolVersion = "0.8.12"
}

android {
    namespace = "com.intempt.core"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 23
        buildConfigField("String", "sdkVersion", "\"${project.property("VERSION")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // In defaultConfig, not buildTypes.release: consumer rules must ship with the AAR
        // regardless of which variant a host app builds against.
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        debug {
            // Without this AGP does not instrument the unit-test classpath, jacoco records
            // no execution data, and the report renders a confident 0% — worse than no
            // report, because it looks like a measurement.
            enableUnitTestCoverage = true
        }

        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            testProguardFiles("proguard-test.pro")
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
        unitTests.all {
            // Prod credentials for ProdDeliveryTest, from gitignored local.properties or the
            // environment. Passed as system properties rather than BuildConfig fields so they
            // never reach the published artifact. Absent means that test skips.
            val creds =
                Properties().apply {
                    val f = rootProject.file("local.properties")
                    if (f.exists()) f.inputStream().use { load(it) }
                }

            fun cred(
                prop: String,
                env: String,
            ): String = (creds.getProperty(prop) ?: System.getenv(env))?.takeIf { v -> v.isNotBlank() } ?: ""

            // Opt-in, not on by default. These tests call the live ingestion endpoint, so
            // leaving them in the ordinary unit-test run couples every pull request to an
            // external service: a transient blip failed a PR here with
            // "SocketException: Unexpected end of file from server" on code that was fine.
            // Enable with -Pintempt.prodTests=true, which CI does in its own non-gating job.
            it.systemProperty(
                "intempt.prodTests",
                (project.findProperty("intempt.prodTests") as String? ?: "false"),
            )
            it.systemProperty("intempt.apiKey", cred("intempt.apiKey", "INTEMPT_API_KEY"))
            it.systemProperty("intempt.organization", cred("intempt.organization", "INTEMPT_ORGANIZATION_ID"))
            it.systemProperty("intempt.project", cred("intempt.project", "INTEMPT_PROJECT_ID"))
            it.systemProperty("intempt.sourceId", cred("intempt.sourceId", "INTEMPT_SOURCE_ID"))

            // A JVM per test class. This is containment, not a fix.
            //
            // Production code still starts coroutines on hardcoded dispatchers in scopes
            // that outlive the test that triggered them. When such a coroutine throws after
            // its test has finished, the exception reaches the global handler, and
            // kotlinx.coroutines.test reports it against whichever test starts next —
            // in whichever class. So a single leak presents as an unrelated failure, and
            // "fixing" the named test does nothing but move the failure elsewhere.
            //
            // Removing this was tried on the strength of one clean local run and CI
            // disagreed: ModificationsUnitTest failed with UncaughtExceptionsBeforeTest,
            // a class whose own dispatcher is injected and which therefore cannot be the
            // source. Slower hardware changes the timing enough to surface leaks that a
            // fast local machine hides, so this stays until every scope is injectable.
            it.forkEvery = 1
        }
    }
}

dependencies {
    implementation(libs.compose.ui)
    implementation(libs.compose.material)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.dagger)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.ui.android)
    implementation(libs.core.ktx)
    kapt(libs.dagger.compiler)
    implementation(kotlin("reflect"))
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.serialization)
    // api, not implementation. JsonElement and JsonObject appear in the SDK's PUBLIC API —
    // ModificationProvider.getByGroup/getByName return JsonElement?, and
    // Intempt.recommendation returns JsonObject?. Declared as implementation, the type was
    // absent from a consumer's compile classpath, so calling recommendation(), experiment or
    // personalization failed to compile in the host app with
    // "Cannot access class 'kotlinx.serialization.json.JsonElement'" and nothing documented
    // that the consumer had to add kotlinx-serialization themselves.
    //
    // Found by writing the e2e suite in :sample, which consumes this library the way a
    // customer does. Version matches what ktor already resolves (1.5.1), so this exposes the
    // artifact that was on the runtime classpath all along rather than adding a new one.
    api(libs.kotlinx.serialization.json)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(kotlin("script-runtime"))
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
// Pinned to 2.13.x, NOT the latest. Jackson 2.16+ ships
// databind/util/ExceptionUtil, whose isFatal() references java.lang.BootstrapMethodError
// — a class that does not exist below API 26. Loading ObjectMapper therefore throws
// NoClassDefFoundError on Android 7 and 7.1, and because that is an Error rather than an
// Exception it escapes Intempt.initialize's catch and kills the host app at launch.
//
// Verified on an API 24 emulator: with 2.18.3 the sample app dies before its first frame.
// Do not bump this without running :sample on an API 24 image; neither Robolectric nor
// lint can see it. Robolectric runs on the JVM, where BootstrapMethodError exists, so a
// @Config(sdk=[24]) test passes while a real device crashes.
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.5")
// https://mvnrepository.com/artifact/com.github.bumptech.glide/glide
    implementation("com.github.bumptech.glide:glide:4.15.1")
// https://mvnrepository.com/artifact/org.projectlombok/lombok
    compileOnly("org.projectlombok:lombok:1.18.36")
// https://mvnrepository.com/artifact/androidx.lifecycle/lifecycle-runtime-ktx
    runtimeOnly("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Instrumented tests. These artifacts were already declared in the version catalog
    // but never wired to a source set, because app/src/androidTest did not exist.
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.runner.test)
    androidTestImplementation(libs.androidx.rules.test)
    androidTestImplementation(libs.androidx.core.test)

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.logback.classic)
}

mavenPublishing {
    coordinates(
        groupId = project.findProperty("GROUP") as String,
        artifactId = project.findProperty("ARTIFACT_ID") as String,
        version = project.findProperty("VERSION") as String,
    )

    pom {
        name = project.findProperty("POM_NAME") as String
        description = project.findProperty("POM_DESCRIPTION") as String
        inceptionYear = project.findProperty("POM_INCEPTION_YEAR") as String
        url = project.findProperty("POM_URL") as String

        licenses {
            license {
                name = project.findProperty("POM_LICENCE_NAME") as String
                url = project.findProperty("POM_LICENCE_URL") as String
                distribution = project.findProperty("POM_LICENCE_DIST") as String
            }
        }
        developers {
            developer {
                id = project.findProperty("POM_DEVELOPER_ID") as String
                name = project.findProperty("POM_DEVELOPER_NAME") as String
                url = project.findProperty("POM_DEVELOPER_URL") as String
            }
        }
        scm {
            url = project.findProperty("POM_SCM_URL") as String
            connection = project.findProperty("POM_SCM_CONNECTION") as String
            developerConnection = project.findProperty("POM_SCM_DEV_CONNECTION") as String
        }
    }

    // Releases are signed (RELEASE_SIGNING_ENABLED=true in gradle.properties). In CI the key
    // is provided in-memory via env vars ORG_GRADLE_PROJECT_signingInMemoryKey /
    // ...signingInMemoryKeyPassword. Local publishToMavenLocal can skip with -PSKIP_SIGNING=true.
    signing {
        isRequired = project.findProperty("SKIP_SIGNING") != "true"
    }
}

// Coverage on the JVM unit tests.
//
// Known limitation: every unit-test file here runs under RobolectricTestRunner, and
// Robolectric's sandbox classloader reloads application classes, discarding JaCoCo's
// instrumentation. The report therefore currently shows near-zero regardless of what the
// tests actually exercise — only HttpStatusPolicyTest, which touches no Android types,
// registers. The wiring is correct and left in place; the number becomes meaningful either
// when non-Robolectric unit tests exist or when coverage is taken from the instrumented
// suite via createDebugCoverageReport, which is the path Mixpanel uses.
//
// Deliberately not gated on a threshold. A floor enforced against a broken measurement
// would be worse than no floor.
tasks.register<JacocoReport>("jacocoTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
    // AGP 8 nests javac output one level deeper than AGP 7 did
    // (intermediates/javac/debug/compileDebugJavaWithJavac/classes), and getting this path
    // wrong yields a report that renders a confident 0% rather than failing.
    val excluded =
        listOf(
            "**/R.class",
            "**/R$*.class",
            "**/BuildConfig.*",
            "**/Manifest*.*",
            "**/*_Factory*.*",
            "**/*_MembersInjector*.*",
            // Dagger-generated
            "**/Dagger*.*",
        )
    classDirectories.setFrom(
        files(
            fileTree("${layout.buildDirectory.get()}/tmp/kotlin-classes/debug") { exclude(excluded) },
            fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug") { exclude(excluded) },
        ),
    )
    sourceDirectories.setFrom(files("src/main/java"))
    executionData.setFrom(fileTree(layout.buildDirectory).include("**/*.exec", "**/*.ec"))
}

ktlint {
    android.set(true)
    // The vendored substrate is Java and deliberately kept close to upstream; ktlint only
    // covers Kotlin, so this governs our own code.
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/generated/") }
    }
}
