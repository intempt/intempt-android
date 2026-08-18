pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }


    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "intempt-android"
include(":app")

// Optional push-notification module. Depends on :app (not the other way around): a host app that
// wants push adds this module explicitly; :app itself never references anything in it directly
// (see com.intempt.core.internal.PushBridge for the presence-gated reflection call).
include(":push")

// A host app that consumes the library the way a customer does. Not published — it exists so
// that "the SDK works" is something the build can demonstrate rather than assert.
include(":sample")
 
// Mutation testing for the SDK's pure-JVM decision logic. PIT cannot run against an Android
// library module, so this module applies `java-library` and compiles the same source files out of
// `:app`. Not published, not part of the SDK artifact. See mutation/build.gradle.kts.
include(":mutation")

// Generates a Baseline Profile for the SDK's cold-start init path by driving :sample's
// MainActivity through Macrobenchmark. Not published, not part of the SDK artifact; its
// output is copied by hand into app/src/main/baselineProfiles (see CLAUDE.md notes on
// library baseline profile packaging — AGP bundles that source set into the AAR directly,
// no consumer plugin needed on a library module).
include(":baselineprofile")
