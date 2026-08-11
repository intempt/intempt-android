plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.vanniktech.deployer)
    alias(libs.plugins.gradleup.nmcp)
    id("kotlin-kapt")
    id("maven-publish")
    id("kotlin-parcelize")
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
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(kotlin("script-runtime"))
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    implementation(platform("com.google.firebase:firebase-bom:33.10.0"))
// https://mvnrepository.com/artifact/com.fasterxml.jackson.module/jackson-module-kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.18.3")
// https://mvnrepository.com/artifact/com.github.bumptech.glide/glide
    implementation("com.github.bumptech.glide:glide:4.15.1")
// https://mvnrepository.com/artifact/org.projectlombok/lombok
    compileOnly("org.projectlombok:lombok:1.18.36")
// https://mvnrepository.com/artifact/androidx.lifecycle/lifecycle-runtime-ktx
    runtimeOnly("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

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
        version = project.findProperty("VERSION") as String
    )

    pom {
        name = project.findProperty("POM_NAME") as String
        description = project.findProperty("POM_DESCRIPTION") as String
        inceptionYear= project.findProperty("POM_INCEPTION_YEAR") as String
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