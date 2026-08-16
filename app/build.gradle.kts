import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
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
    alias(libs.plugins.animalsniffer)
    alias(libs.plugins.binary.compatibility.validator)
    alias(libs.plugins.dokka)
    alias(libs.plugins.detekt)
    alias(libs.plugins.dependency.check)
}

// ABI-compatibility gate: `apiCheck` fails the build if the public surface drifted from the
// committed app/api/app.api file without that file being regenerated via `apiDump`. It is a
// guardrail against an accidental breaking change slipping in unnoticed, not a scoring exercise —
// intentional API changes just need `./gradlew :app:apiDump` run and the updated file committed.

jacoco {
    toolVersion = "0.8.12"
}

// Excludes the cross-module SPI marked @InternalIntemptApi from the binary-compatibility gate.
// Those classes were widened from `internal` to public solely so the optional :push module (a
// separate Gradle compilation unit) can see them; they are not part of the public API contract and
// must not show up in app/api/app.api.
apiValidation {
    nonPublicMarkers.add("com.intempt.core.internal.InternalIntemptApi")
    // The marker annotation class itself is technically public (Kotlin requires a @RequiresOptIn
    // annotation class to be at least as visible as what it gates) but is not meant to be part of
    // the public API surface either — a host app has no legitimate reason to reference it.
    ignoredPackages.add("com.intempt.core.internal")
}

// Generates an HTML API reference from the KDoc already on com.intempt.core.Intempt and its
// nested Logging/Tracking objects. Local-only for now (see CONTRIBUTING.md "Generating API
// docs"); not wired into CI or publish.yml — there is no hosting target for it yet, and adding
// one is a separate decision from having the docs.
//
// Run: ./gradlew :app:dokkaHtml   (output: app/build/dokka/html/index.html)
tasks.dokkaHtml.configure {
    moduleName.set("Intempt Android SDK")
    dokkaSourceSets.configureEach {
        // The public surface is com.intempt.core.Intempt (and its nested Logging/Tracking
        // objects); everything else is internal implementation (queue, delivery, autocapture)
        // living in subpackages, which stay suppressed below.
        perPackageOption {
            matchingRegex.set(""".*""")
            suppress.set(true)
        }
        perPackageOption {
            matchingRegex.set("""com\.intempt\.core""")
            suppress.set(false)
        }
        // Cross-module SPI for :push, not part of the public API — keep it out of generated docs.
        perPackageOption {
            matchingRegex.set("""com\.intempt\.core\.internal.*""")
            suppress.set(true)
        }
    }
}

// Scans this module's resolved dependency graph (transitives included) against the National
// Vulnerability Database. Targets :app specifically -- not the :mutation sidecar, which recompiles
// a handful of :app's own sources with no Android/network/serialization dependencies and so has
// nothing meaningful to scan.
//
// Gated on CVSS >= 9 (critical) rather than the default of "any known CVE": a small SDK with a
// handful of direct dependencies (Ktor, Dagger, AndroidX) regularly carries medium/high CVEs in
// transitives that are unreachable from this codebase (e.g. server-only code paths), and gating on
// those would make the check noise a maintainer learns to ignore or bypass. Critical, exploitable
// vulnerabilities should still fail the build outright.
dependencyCheck {
    failBuildOnCVSS = 9.0f
    // JVM/Android dependencies only. The default analyzer set includes ones aimed at other
    // ecosystems (Node, .NET, Ruby, PHP, Swift) that this project has nothing for; disabling them
    // is a meaningful chunk of the scan's runtime with zero loss of coverage here.
    analyzers.assemblyEnabled = false
    analyzers.nodeEnabled = false
    analyzers.nodeAuditEnabled = false
    analyzers.nuspecEnabled = false
    analyzers.nugetconfEnabled = false
    analyzers.retirejs.enabled = false
    analyzers.golangDepEnabled = false
    analyzers.golangModEnabled = false
    analyzers.cocoapodsEnabled = false
    analyzers.swiftEnabled = false
    analyzers.swiftPackageResolvedEnabled = false
    analyzers.rubygemsEnabled = false
    analyzers.cmakeEnabled = false
    analyzers.autoconfEnabled = false
    analyzers.opensslEnabled = false
    analyzers.msbuildEnabled = false
    // Speeds up and stabilizes CI: an NVD API key raises the feed's rate limit drastically. Absent
    // (forks without the secret), the analyzer still runs, just slower -- it does not fail the
    // build. See INTEMPT_NVD_API_KEY in .github/workflows/ci.yml.
    nvd.apiKey = System.getenv("INTEMPT_NVD_API_KEY")
    formats = listOf("HTML", "JSON")
    outputDirectory = "$buildDir/reports/dependency-check"
}

// API-compatibility gate, checked against the android-api-level-23 signature rather than inferred.
//
// Adopted from mixpanel-android, which runs the same plugin — it was the one build-quality check
// they had and we did not, and it is aimed squarely at the bug class that has hurt this SDK most.
// Three separate crashes on this branch were calls that do not exist at minSdk: Jackson reaching
// java.lang.BootstrapMethodError (API 26) and killing every host app on Android 7, java.util.Base64
// (API 26) in the auth path, and Map.putIfAbsent (API 24) in a test helper.
//
// Lint's NewApi did not catch any of them. It reads our sources, so a call inside a dependency's
// bytecode is invisible to it, and the Base64 one it did see only after checkTestSources was
// enabled. AnimalSniffer checks the compiled artifact against a signature file, so a dependency
// reaching for a missing class fails the build instead of the app.
//
// The plugin's own default for ignoreFailures is false, so a violation fails the build. Not set
// explicitly because the property is not reachable from the Kotlin DSL in 2.0.0 (it is private in a
// supertype); asserted below instead, so the gate cannot silently become advisory.
animalsniffer {
    // One documented exclusion class, verified individually rather than waved away — an exclusion
    // added to make a gate green is how the gate stops meaning anything.
    //
    // (FirebaseService.kt's NotificationChannel/NotificationManager exclusion moved out along with
    // the file itself when push notifications became the separate :push module — see that
    // module's own animalsniffer setup, fast-followed rather than blocking this move.)
    //
    // java.lang.{Long,Boolean,Integer}: the static `hashCode(primitive)` overloads are API 24, and
    //   they appear only inside the hashCode() that Kotlin generates for a data class with a
    //   primitive field — we never call them. D8 backports them unconditionally, so they are
    //   rewritten before they reach a device.
    //
    //   That last sentence is an assumption about the toolchain, not something to take on trust, so
    //   it is pinned by DataClassHashCodeOnDeviceTest in sample/src/androidTest — which calls
    //   hashCode() on the affected shapes and runs on the API 23 emulator in CI. If the backport
    //   ever stops happening, that test fails on-device rather than this exclusion hiding it.
    ignore(
        "java.lang.Long",
        "java.lang.Boolean",
        "java.lang.Integer",
    )
}

// A warning nobody reads is not a gate, and the default is the only thing making this one real —
// so it is checked rather than trusted. If a future plugin version flips the default, this fails at
// configuration time instead of turning every API violation into a log line.
gradle.taskGraph.whenReady {
    tasks.withType(ru.vyarus.gradle.plugin.animalsniffer.AnimalSniffer::class.java).configureEach {
        check(!ignoreFailures) {
            "animalsniffer has ignoreFailures=true, which downgrades every minSdk violation to a " +
                "warning. This gate exists because three separate API-level crashes shipped on this " +
                "branch; it must fail the build."
        }
    }
}

android {
    namespace = "com.intempt.core"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = 23

        // Explicit, and matching compileSdk. Only :sample set this before, so the library itself
        // inherited whatever AGP defaulted to — which for a library module means the manifest
        // declares no targetSdk and behaviour is decided by the consuming app. That is mostly
        // harmless but it is not a decision anyone made, and it left the two modules disagreeing.
        // docs/android-sdk-requirements.md documents no reason to pin lower.
        targetSdk = 35

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

            // testProguardFiles("proguard-test.pro") — the file does not exist. Referenced
            // but never created, so it is latent: harmless today because no test task runs
            // against the release variant, and an immediate build failure the moment anyone
            // sets testBuildType = "release". Commented out rather than an empty file added,
            // so the intent is visible if someone wants it back.
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // No packaging{} block. Removing AGP's default /META-INF/LICENSE and /META-INF/NOTICE
    // excludes made ours ship, and also made every transitive dependency's copy ship — which
    // collided and broke :app:mergeDebugAndroidTestJavaResource in CI. Those excludes exist
    // precisely to stop that.
    //
    // So the files are named intempt_LICENSE.txt / intempt_NOTICE.txt instead. They are not
    // matched by the default excludes, cannot collide with anyone else's, and still travel
    // inside the artifact, which is what Apache 2.0 §4(a) and §4(d) require. NOTICE names all
    // eight derived files against their upstream paths.

    lint {
        // Test sources too, for the same reason as :sample — an API-24 call inside a test is
        // still a call that cannot run at minSdk.
        checkTestSources = true
        error += "NewApi"
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

            // The fix for "coverage reports 0.1%". Robolectric loads classes through its own
            // sandbox classloader, and JaCoCo skips classes it cannot attribute to a source
            // location, so nearly everything Robolectric touched was dropped from the report.
            // The number looked catastrophic while the tests were fine, which is worse than no
            // report: it reads as a measurement.
            it.extensions.configure(JacocoTaskExtension::class.java) {
                isIncludeNoLocationClasses = true
                excludes = listOf("jdk.internal.*")
            }
        }
    }
}

dependencies {
    // The signature the sources are checked against. Must track minSdk — bump both together, or
    // the gate silently verifies against the wrong API level.
    add("signature", "net.sf.androidscents.signature:android-api-level-23:6.0_r3@signature")

    implementation(libs.dagger)
    implementation(libs.androidx.lifecycle.process)
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
// This used to report near-zero and carried a note saying the measurement was broken and so
// deliberately ungated. The measurement was broken: Robolectric's sandbox classloader reloads
// application classes and discards JaCoCo's instrumentation, and JaCoCo then skips classes it
// cannot attribute to a source location — so almost everything the tests actually exercised was
// dropped from the report. That is fixed in the test task's JacocoTaskExtension above
// (isIncludeNoLocationClasses, plus a jdk.internal.* exclude), and the number below is real.
//
// A broken measurement is worse than none, because it reads as a measurement. Now that it is
// honest it can carry a floor — see jacocoCoverageVerification.
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

    // Scoped to the coverage output directory, not the whole build tree.
    //
    // This was `fileTree(layout.buildDirectory).include("**/*.exec", "**/*.ec")`, which declares
    // every file under build/ as an input. Gradle then refuses to run this task alongside anything
    // else that writes there — `jacocoTestReport` together with `ktlintCheck` fails outright with an
    // implicit-dependency validation error. CI never hit it because those are separate invocations,
    // so it sat as a landmine for the first person to combine them locally.
    //
    // AGP 8 writes to outputs/unit_test_code_coverage/<variant>/; the jacoco/ path is the AGP 7
    // location, kept so the report does not silently read nothing if the path moves back.
    executionData.setFrom(
        fileTree(layout.buildDirectory) {
            include("outputs/unit_test_code_coverage/**/*.exec")
            include("jacoco/*.exec")
        },
    )
}

// The coverage floor, enforced in CI.
//
// The target is 85%. These numbers are not 85%, and setting them there today would fail every
// PR — including ones that raise coverage — which trains people to bypass the gate. Setting 85
// and excluding whatever fails it would be the same thing wearing a disguise.
//
// So the floor is a ratchet, pinned just under the measured value. It cannot be met by writing
// no tests and it cannot be lowered without the diff saying so. Every increase moves it up; the
// number in this file is always a real measurement, never an aspiration.
//
// Raise these as coverage rises. The gap to 85 is tracked, not hidden: the largest remaining
// holes are LifecycleCallbackService and the other autocapture components, which need
// instrumented tests rather than JVM ones.
// Measured 2026-08-14 (post :push extraction): LINE 2131/3176 = 67.1%, BRANCH 491/1087 = 45.2%.
//
// Both rose (58.4% -> 67.1% line, 39.4% -> 45.2% branch) once FirebaseService,
// NotificationDispatcherActivity, WebhookService and the push-notification models moved out to
// the separate :push module — exactly the coverage gap this file's previous comment named. The
// same denominator shrink also means the floor moved for the honest reason (less uncovered surface
// to divide by), not because any test was removed.
//
// Re-baselined deliberately and stated rather than quietly adjusted. Re-measure with
// `./gradlew :app:jacocoTestReport` and raise these as coverage rises.
val coverageFloorLine = 0.66
val coverageFloorBranch = 0.44

tasks.register<JacocoCoverageVerification>("jacocoCoverageVerification") {
    dependsOn("jacocoTestReport")

    // Same class/source wiring as the report task. Pointing verification at a different set than
    // the report is how a gate ends up green against a number nobody is looking at.
    classDirectories.setFrom(tasks.named<JacocoReport>("jacocoTestReport").get().classDirectories)
    sourceDirectories.setFrom(tasks.named<JacocoReport>("jacocoTestReport").get().sourceDirectories)
    executionData.setFrom(tasks.named<JacocoReport>("jacocoTestReport").get().executionData)

    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = coverageFloorLine.toBigDecimal()
            }
            limit {
                // Branch coverage is the one that catches untested error paths, which is where
                // this SDK's defects have actually been. Gated separately so a rise in line
                // coverage cannot mask a fall here.
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = coverageFloorBranch.toBigDecimal()
            }
        }
    }
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

// Static analysis for complexity, duplication, and code smells — ktlint above is formatting
// only and does not look at any of that.
//
// buildUponDefaultConfig pulls in detekt's own baked-in ruleset so config.yml only needs to
// state the handful of deviations from it, not reproduce the whole thing.
//
// The vendored queue/ package (see its own file-header comments) is excluded: it is kept
// deliberately close to upstream Mixpanel Java, detekt's complexity/style rules do not apply
// to Java sources anyway, and diverging it from upstream to satisfy a Kotlin linter would
// defeat the point of vendoring it.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$projectDir/config/detekt/detekt.yml"))
    baseline = file("$projectDir/config/detekt/baseline.xml")
    source.setFrom(
        files(
            "src/main/java",
            "src/test/java",
        ),
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude("**/core/queue/**")
    reports {
        html.required.set(true)
        xml.required.set(false)
        sarif.required.set(false)
        txt.required.set(false)
    }
}
