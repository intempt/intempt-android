//plugins {
//    alias(libs.plugins.android.library)
//    alias(libs.plugins.kotlin.android)
//    alias(libs.plugins.vanniktech.deployer)
//    alias(libs.plugins.gradleup.nmcp)
//
////    id("com.vanniktech.maven.publish") version "0.28.0"
//    //id("com.gradleup.nmcp") version "0.0.8"
//
//}
//
//android {
//    namespace = "com.intempt.sdk"
//    compileSdk = 34
//
////    publishing {
////        singleVariant("release") {
////            withSourcesJar()
////        }
////    }
//
//    defaultConfig {
//        minSdk = 31
//        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
//
//
//    }
//
//    buildTypes {
//        release {
//            isMinifyEnabled = false
//            proguardFiles(
//                getDefaultProguardFile("proguard-android-optimize.txt"),
//                "proguard-rules.pro"
//            )
//        }
//    }
//    compileOptions {
//        sourceCompatibility = JavaVersion.VERSION_1_8
//        targetCompatibility = JavaVersion.VERSION_1_8
//
//
//    }
//    kotlinOptions {
//        jvmTarget = "1.8"
//
//    }
//}
//
//dependencies {
//    implementation(kotlin("reflect"))
//    implementation(libs.ktor.client.core)
//    implementation(libs.ktor.client.android)
//    implementation(libs.ktor.client.cio)
//    implementation(libs.ktor.serialization.kotlinx.json)
//    implementation(libs.ktor.client.content.negotiation)
//    implementation(libs.ktor.client.serialization)
//    implementation(libs.kotlinx.coroutines.core)
//    implementation(libs.kotlinx.coroutines.android)
//
//    implementation(libs.androidx.core.ktx)
//    implementation(libs.androidx.appcompat)
//    implementation(libs.material)
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.androidx.junit)
//    androidTestImplementation(libs.androidx.espresso.core)
//}
//
//mavenPublishing {
//    coordinates(
//        groupId = project.findProperty("GROUP") as String,
//        artifactId = project.findProperty("ARTIFACT_ID") as String,
//        version = project.findProperty("VERSION") as String
//    )
//
//    pom {
//        name = project.findProperty("POM_NAME") as String
//        description = project.findProperty("POM_DESCRIPTION") as String
//        inceptionYear= project.findProperty("POM_INCEPTION_YEAR") as String
//        url = project.findProperty("POM_URL") as String
//
//        licenses {
//            license {
//                name = project.findProperty("POM_LICENCE_NAME") as String
//                url = project.findProperty("POM_LICENCE_URL") as String
//                distribution = project.findProperty("POM_LICENCE_DIST") as String
//            }
//        }
//        developers {
//            developer {
//                id = project.findProperty("POM_DEVELOPER_ID") as String
//                name = project.findProperty("POM_DEVELOPER_NAME") as String
//                url = project.findProperty("POM_DEVELOPER_URL") as String
//            }
//        }
//        scm {
//            url = project.findProperty("POM_SCM_URL") as String
//            connection = project.findProperty("POM_SCM_CONNECTION") as String
//            developerConnection = project.findProperty("POM_SCM_DEV_CONNECTION") as String
//        }
//    }
//
//    signing{
//        useGpgCmd()
//    }
//
//}


plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)

    id("kotlin-kapt")
    `maven-publish`
}

android {
    namespace = "com.intempt.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
}

dependencies {
    implementation(libs.dagger)
    implementation(libs.androidx.lifecycle.process)
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
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
                groupId = project.findProperty("GROUP") as String
                artifactId = project.findProperty("ARTIFACT_ID") as String
                version = project.findProperty("VERSION") as String
            }
        }
    }
    repositories {
        mavenLocal()
    }
}