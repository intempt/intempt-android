import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

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

    // INT-3916 — the BOM below owns the version; a pinned one silently wins over it.
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
    implementation("com.google.firebase:firebase-messaging")
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

/**
 * The gate that 4.0.1 did not have.
 *
 * `readValue<T>()` from jackson-module-kotlin is inline + reified: the compiler expands it to an
 * anonymous `TypeReference<T>` subclass, whose constructor recovers T at runtime from the class
 * file's `Signature` attribute. R8 discards `Signature` unless asked to keep it, so in the
 * minified 4.0.1 AAR that constructor threw `IllegalArgumentException` and **every received push
 * was dropped** — silently, with a log line that blamed the payload.
 *
 * Nothing in this repository could catch that. The defect does not exist before R8, so no JVM
 * test sees it; `sample/proguard-rules.pro` is deliberately empty so a missing consumer rule is
 * not masked, but nothing exercises the push parse path in a minified build.
 *
 * So this asserts on the shipped artifact instead, and deliberately bans the whole construct
 * rather than checking that the keep rule is present: a keep rule is a thing that can be dropped
 * again, while the `Class<T>` overload cannot break this way at all. If you genuinely need a
 * generic target type here, you need `TypeReference` AND `-keepattributes Signature`, and you
 * should be made to say so out loud — which is what failing this task does.
 */
val verifyNoReifiedJacksonInReleaseAar by tasks.registering {
    description = "Fails if the release AAR references Jackson's TypeReference, or lost its Signature keep rule."
    group = "verification"
    dependsOn("assembleRelease")

    val aar = layout.buildDirectory.file("outputs/aar/push-release.aar")
    inputs.file(aar)
    outputs.upToDateWhen { false }

    doLast {
        val aarFile = aar.get().asFile
        require(aarFile.exists()) { "expected the release AAR at $aarFile, but it is not there" }

        val typeRef = "com/fasterxml/jackson/core/type/TypeReference".toByteArray(Charsets.UTF_8)
        val offenders = mutableListOf<String>()
        var proguardRules: String? = null
        var classesSeen = 0

        ZipFile(aarFile).use { outer ->
            val proguardEntry = outer.getEntry("proguard.txt")
            if (proguardEntry != null) {
                proguardRules = outer.getInputStream(proguardEntry).readBytes().toString(Charsets.UTF_8)
            }
            val classesEntry =
                outer.getEntry("classes.jar")
                    ?: error("the release AAR has no classes.jar — this check cannot verify anything")

            ZipInputStream(outer.getInputStream(classesEntry)).use { zin ->
                var entry = zin.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".class")) {
                        classesSeen++
                        val bytes = zin.readBytes()
                        if (bytes.indexOfSlice(typeRef) >= 0) offenders += entry.name
                    }
                    entry = zin.nextEntry
                }
            }
        }

        // A check that verifies nothing must fail, not pass. An empty or unreadable jar is
        // exactly how this kind of gate goes quietly green forever.
        require(classesSeen > 0) {
            "read 0 class files out of ${aarFile.name}; this check proved nothing and is therefore failing"
        }

        require(offenders.isEmpty()) {
            buildString {
                appendLine("The release AAR references Jackson's TypeReference in ${offenders.size} class(es):")
                offenders.sorted().forEach { appendLine("  $it") }
                appendLine()
                appendLine("This is how 4.0.1 shipped a build that dropped every push. A reified")
                appendLine("mapper.readValue<T>(json) expands to an anonymous TypeReference subclass whose")
                appendLine("constructor needs the Signature attribute that R8 removes.")
                appendLine()
                appendLine("Use the Class<T> overload instead:")
                appendLine("    mapper.readValue(json, Foo::class.java)")
                appendLine()
                appendLine("If the target type is genuinely generic and TypeReference is unavoidable, keep")
                appendLine("-keepattributes Signature in push/consumer-rules.pro and relax this check")
                appendLine("deliberately, in its own commit, with the reason written down.")
            }
        }

        val rules =
            proguardRules
                ?: error("the release AAR ships no proguard.txt, so no consumer rule reaches host apps")
        require(rules.lineSequence().any { it.trim().startsWith("-keepattributes") && "Signature" in it }) {
            "push/consumer-rules.pro no longer keeps the Signature attribute. Even with the Class<T> " +
                "overload in place this rule is the guard for any future reified Jackson call, and it " +
                "ships to every consuming app's R8 run. Restore -keepattributes Signature."
        }

        logger.lifecycle(
            "verifyNoReifiedJacksonInReleaseAar: $classesSeen classes checked, " +
                "no TypeReference reference, Signature keep rule present.",
        )
    }
}

/** Helper: ByteArray has no indexOf(ByteArray) in the stdlib. */
fun ByteArray.indexOfSlice(needle: ByteArray): Int {
    if (needle.isEmpty() || needle.size > size) return -1
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
        return i
    }
    return -1
}

tasks.named("check") { dependsOn(verifyNoReifiedJacksonInReleaseAar) }
