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
    id("info.solidsoft.pitest") version "1.15.0"
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
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
            include("android/util/Log.java")
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
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Android bundles org.json; on the JVM it comes from Maven. Same API, so TrackPayloadBuilder
    // compiles and behaves identically here.
    implementation("org.json:json:20240303")
}

tasks.test {
    useJUnit()
}

pitest {
    pitestVersion.set("1.16.1")
    targetClasses.set(
        listOf(
            "com.intempt.core.queue.HttpStatusPolicy*",
            "com.intempt.core.queue.TrackPayloadBuilder*",
            "com.intempt.core.queue.QueueConfig*",
            "com.intempt.core.queue.QueueLog*",
            "com.intempt.core.queue.EventDedupKey*",
        ),
    )
    targetTests.set(
        listOf(
            "com.intempt.core.queue.HttpStatusPolicyTest",
            "com.intempt.core.queue.PureJvmQueueTest",
            "com.intempt.core.queue.TrackPayloadBuilderPureTest",
            "com.intempt.core.queue.QueueLogFallbackTest",
            "com.intempt.core.queue.EventDedupKeyPureTest",
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
