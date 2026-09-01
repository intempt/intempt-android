// Mutation testing for the SDK's pure-JVM decision logic.
//
// PIT cannot run against `:app` directly. `info.solidsoft.pitest` requires the `java` plugin and
// registers no extension and no tasks on a module that applies `com.android.library`; the
// Android-aware fork `pl.droidsonroids.pitest` throws NullPointerException against AGP 8.6.1 and
// its last release predates AGP 8. Both were tried and removed rather than left half-applied,
// because a plugin that silently produces no mutants reads as coverage.
//
// This module is the way around that without restructuring the SDK. It applies `java-library`, so
// PIT works natively, and compiles the *same source files* out of `:app` rather than a copy — the
// srcDir below points into app/src/main/java. No production code moves, nothing is duplicated, and
// there is no second copy to drift.
//
// Only classes with no Android dependency can be included. That is the constraint, not a
// limitation of intent: the classes that qualify happen to be the ones mutation testing pays most
// for, because they are pure decisions with no I/O. `HttpStatusPolicy` is the delete-versus-retry
// predicate — the single most consequential branch in the SDK, since getting it wrong either drops
// events permanently or wedges the queue forever.
//
// This module is not published and is not part of the SDK artifact.

plugins {
    id("java-library")
    // Kotlin, added for 3.0. The queue substrate this module was built for is Java, but the types
    // 3.0 introduced — IntemptValue, IntemptError, IntemptCredentials, Product, InstanceId — are
    // Kotlin, pure, and exactly the kind of decision-dense code mutation testing is for. Leaving
    // them out would have meant the gate covering the transport and none of the new surface.
    kotlin("jvm")
    id("info.solidsoft.pitest") version "1.15.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Pinned to match compileJava. Kotlin otherwise defaults to the toolchain's 21 and Gradle refuses
// the mismatch — the two compilers feed one classpath here, so they have to agree.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

sourceSets {
    main {
        java {
            // The stub source dir carries a no-op android.util.Log so the classes below compile
            // without android.jar — QueueLog imports it, and QueueConfig and TrackPayloadBuilder
            // both call QueueLog. Without it this module could only ever cover HttpStatusPolicy.
            setSrcDirs(listOf("../app/src/main/java", "src/main/stub"))
            // Everything here must compile without android.jar. Adding a class that imports
            // anything from `android.*` will fail this module's compile, which is the intended
            // guard rather than an inconvenience.
            include("com/intempt/core/queue/HttpStatusPolicy.java")
            include("com/intempt/core/queue/TrackPayloadBuilder.java")
            include("com/intempt/core/queue/QueueConfig.java")
            include("com/intempt/core/queue/QueueLog.java")
            include("com/intempt/core/queue/OfflineMode.java")
            include("com/intempt/core/queue/ProxyServerInteractor.java")
            include("com/intempt/core/queue/EventDedupKey.java")
            include("com/intempt/core/queue/DeliveryRetryPolicy.java")
            include("android/util/Log.java")
        }
        kotlin {
            // Same rule as the Java list above: everything here must compile without android.jar,
            // so a type that grows an `android.*` import fails this module rather than silently
            // dropping out of the mutation gate.
            setSrcDirs(listOf("../app/src/main/java"))
            include("com/intempt/core/types/IntemptValue.kt")
            include("com/intempt/core/types/IntemptError.kt")
            include("com/intempt/core/types/IntemptCredentials.kt")
            include("com/intempt/core/types/InstanceId.kt")
            include("com/intempt/core/types/Product.kt")
            include("com/intempt/core/types/ConsentAction.kt")
            include("com/intempt/core/types/CaptureOptions.kt")
            include("com/intempt/core/types/FeedFields.kt")
            // Added for the flag surface. Before this, `targetClasses` below matched NONE of the
            // six classes that change touched, so the 85/85 was measured entirely over untouched
            // code. Flags.kt is where its pure decisions were moved to precisely so the gate can
            // see them — buildChooseBody's null handling, unwrapFlagValue's type preservation,
            // flagNameOf/flagReasonOf's safe reads and FlagReason.fromWire's fallback.
            include("com/intempt/core/types/Flags.kt")
        }
    }
    test {
        java {
            // Two roots: the shared tests compiled by :app as well, plus mutation-only tests for
            // classes whose dependencies :app's unit-test task cannot provide (org.json resolves to
            // the stub android.jar there and throws "not mocked").
            setSrcDirs(listOf("../app/src/test/java", "src/test/java"))
            include("com/intempt/core/queue/HttpStatusPolicyTest.java")
            include("com/intempt/core/queue/PureJvmQueueTest.java")
            include("com/intempt/core/queue/TrackPayloadBuilderPureTest.java")
            include("com/intempt/core/queue/QueueLogFallbackTest.java")
            include("com/intempt/core/queue/EventDedupKeyPureTest.java")
            include("com/intempt/core/queue/DeliveryRetryPolicyTest.java")
        }
        kotlin {
            // Two roots, mirroring the java block above: the shared tests :app compiles as well,
            // plus mutation-only Kotlin tests for classes whose dependencies :app's unit-test task
            // cannot provide (org.json again — ChooseBodyJsonTest).
            setSrcDirs(listOf("../app/src/test/java", "src/test/java"))
            include("com/intempt/core/types/IntemptValueTest.kt")
            include("com/intempt/core/types/InstanceIdTest.kt")
            include("com/intempt/core/types/IntemptCredentialsTest.kt")
            include("com/intempt/core/types/ContractTypesTest.kt")
            include("com/intempt/core/types/FlagsTest.kt")
            include("com/intempt/core/types/ChooseBodyJsonTest.kt")
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android bundles org.json; on the JVM it comes from Maven. Same API, so TrackPayloadBuilder
    // compiles and behaves identically here.
    implementation("org.json:json:20240303")
    // Flags.kt reads JsonElement/JsonPrimitive. Library only — no @Serializable here, so the
    // serialization COMPILER plugin is deliberately not applied. Same catalog entry :app uses,
    // so there is no second version to drift.
    implementation(libs.kotlinx.serialization.json)
}

tasks.test {
    useJUnit()
}

pitest {
    pitestVersion.set("1.16.1")
    targetClasses.set(
        listOf(
            "com.intempt.core.types.IntemptValue*",
            "com.intempt.core.types.IntemptError*",
            "com.intempt.core.types.IntemptCredentials*",
            "com.intempt.core.types.InstanceId*",
            "com.intempt.core.types.Product*",
            "com.intempt.core.types.ConsentAction*",
            "com.intempt.core.types.FlagReason*",
            "com.intempt.core.types.FlagContext*",
            "com.intempt.core.types.FlagDetail*",
            // Top-level functions in Flags.kt compile into this facade class.
            "com.intempt.core.types.FlagsKt*",
            "com.intempt.core.queue.HttpStatusPolicy*",
            "com.intempt.core.queue.TrackPayloadBuilder*",
            "com.intempt.core.queue.QueueConfig*",
            "com.intempt.core.queue.QueueLog*",
            "com.intempt.core.queue.EventDedupKey*",
            "com.intempt.core.queue.DeliveryRetryPolicy*",
        ),
    )
    targetTests.set(
        listOf(
            "com.intempt.core.types.IntemptValueTest",
            "com.intempt.core.types.InstanceIdTest",
            "com.intempt.core.types.IntemptCredentialsTest",
            "com.intempt.core.types.ContractTypesTest",
            "com.intempt.core.types.FlagsTest",
            "com.intempt.core.types.ChooseBodyJsonTest",
            "com.intempt.core.queue.HttpStatusPolicyTest",
            "com.intempt.core.queue.PureJvmQueueTest",
            "com.intempt.core.queue.TrackPayloadBuilderPureTest",
            "com.intempt.core.queue.QueueLogFallbackTest",
            "com.intempt.core.queue.EventDedupKeyPureTest",
            "com.intempt.core.queue.DeliveryRetryPolicyTest",
        ),
    )
    threads.set(2)
    outputFormats.set(listOf("HTML", "XML"))
    timestampedReports.set(false)

    // The gate. A surviving mutant here is a branch whose behaviour no test pins, which for this
    // class means a status code could silently change category — dropping events that should be
    // retried, or retrying ones that will never succeed.
    mutationThreshold.set(85)
    coverageThreshold.set(85)
}
