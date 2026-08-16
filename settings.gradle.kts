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

// A host app that consumes the library the way a customer does. Not published — it exists so
// that "the SDK works" is something the build can demonstrate rather than assert.
include(":sample")
 
// Mutation testing for the SDK's pure-JVM decision logic. PIT cannot run against an Android
// library module, so this module applies `java-library` and compiles the same source files out of
// `:app`. Not published, not part of the SDK artifact. See mutation/build.gradle.kts.
include(":mutation")
