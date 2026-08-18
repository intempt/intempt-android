plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.deployer)
    id("maven-publish")
    id("kotlin-parcelize")
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

android {
    namespace = "com.intempt.push"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        targetSdk = 35
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro",
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

    lint {
        // Same reason as :app and :sample — an API-24+ call inside a test is still a call
        // that cannot run at minSdk. This module lacked the block; NewApi in test sources
        // went unchecked here even though it's an error everywhere else in the repo.
        checkTestSources = true
        error += "NewApi"
    }
}

dependencies {
    implementation(project(":app"))

    // kotlinx-coroutines-core is `implementation`, not `api`, in :app's build.gradle.kts — not
    // transitively visible here, so it's declared explicitly for FirebaseService's suspend
    // initializeToken() and PushModuleEntryPoint's coroutine bridging.
    implementation(libs.kotlinx.coroutines.core)

    // All `implementation` (not `api`) in :app's build.gradle.kts, so not transitively visible to
    // a sibling module — declared here explicitly for the types the moved push files reference
    // directly: HttpManagerService.post()/get() return ktor's HttpResponse,
    // NotificationDispatcherActivity extends AppCompatActivity and uses LifecycleOwner.lifecycleScope.
    implementation(libs.ktor.client.core)
    implementation(libs.androidx.appcompat)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

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

    testImplementation(libs.junit)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}

mavenPublishing {
    coordinates(
        groupId = project.findProperty("GROUP") as String,
        artifactId = "intempt-push",
        version = project.findProperty("VERSION") as String,
    )

    pom {
        name = "Intempt Push"
        description = "Optional push-notification module for the Intempt Android SDK"
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

    signing {
        isRequired = project.findProperty("SKIP_SIGNING") != "true"
    }
}

ktlint {
    android.set(true)
    ignoreFailures.set(false)
    filter {
        exclude { it.file.path.contains("/generated/") }
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("${rootProject.projectDir}/app/config/detekt/detekt.yml"))
    // Own baseline, not :app's: these findings (broad catches around a reflective/best-effort
    // FCM integration, one long onMessageReceived) are the same shape :app already baselines for
    // the vendored/legacy paths, but they moved here with the files when push became its own
    // module, so they need their own baseline entry rather than inheriting :app's file-line-keyed
    // one (which no longer matches these files' paths).
    baseline = file("$projectDir/config/detekt/baseline.xml")
    source.setFrom(
        files(
            "src/main/java",
        ),
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        xml.required.set(false)
        sarif.required.set(false)
        txt.required.set(false)
    }
}
