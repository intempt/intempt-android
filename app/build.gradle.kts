import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
    signing
}

publishing {
    val secretPropsFile = file("secret.properties")
    val secrets = Properties()

    if (secretPropsFile.exists()) {
        secretPropsFile.inputStream().use { secrets.load(it) }

    } else {
        logger.warn("Warning: secret.properties file not found. Make sure to create it for signing and publishing.")
    }


    publications {
        create<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
                groupId = project.findProperty("GROUP") as String
                artifactId = project.findProperty("ARTIFACT_ID") as String
                version = project.findProperty("VERSION_NAME") as String

                pom {
                    name.set(project.findProperty("POM_NAME") as String)
                    description.set(project.findProperty("POM_DESCRIPTION") as String)
                    url.set(project.findProperty("POM_URL") as String)

                    licenses {
                        license {
                            name.set(project.findProperty("POM_LICENSE_NAME") as String)
                            url.set(project.findProperty("POM_LICENSE_URL") as String)
                            //distribution.set(project.findProperty("POM_LICENSE_DIST") as String)
                        }
                    }

                    developers {
                        developer {
                            id.set(project.findProperty("POM_DEVELOPER_ID") as String)
                            name.set(project.findProperty("POM_DEVELOPER_NAME") as String)
                            email.set(project.findProperty("POM_DEVELOPER_EMAIL") as String)
                        }
                    }

                    scm {
                        connection.set(project.findProperty("POM_SCM_CONNECTION") as String)
                        developerConnection.set(project.findProperty("POM_SCM_DEV_CONNECTION") as String)
                        //url.set(project.findProperty("POM_SCM_URL") as String)
                    }


                }
            }
        }

    }

    repositories {
        maven {
             name = "sonatype"
//            name = "osshr"
            url = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            logger.warn("${secrets["ossrhUsername"]} ${secrets["ossrhPassword"]}")

            credentials {
                username = secrets["ossrhUsername"] as String? ?: ""
                password = secrets["ossrhPassword"] as String? ?: ""
            }
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["release"])
}

android {
    namespace = "com.intempt.sdk"
//    namespace = "io.github.beska0013"
    compileSdk = 34
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
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
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.serialization)
    implementation (libs.kotlinx.coroutines.core)
    implementation (libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}



//plugins {
//    alias(libs.plugins.android.library)
//    alias(libs.plugins.kotlin.android)
//    `maven-publish`
//
//}
//
//android {
//    namespace = "com.intempt.sdk"
//    compileSdk = 34
//
//    defaultConfig {
//        minSdk = 31
//        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
//    }
//    kotlinOptions {
//        jvmTarget = "1.8"
//    }
//}
//
//dependencies {
//    implementation(libs.ktor.client.core)
//    implementation(libs.ktor.client.android)
//    implementation(libs.ktor.client.cio)
//    implementation(libs.ktor.serialization.kotlinx.json)
//    implementation(libs.ktor.client.content.negotiation)
//    implementation(libs.ktor.client.serialization)
//    implementation (libs.kotlinx.coroutines.core)
//    implementation (libs.kotlinx.coroutines.android)
//
//    implementation(libs.androidx.core.ktx)
//    implementation(libs.androidx.appcompat)
//    implementation(libs.material)
//    testImplementation(libs.junit)
//    androidTestImplementation(libs.androidx.junit)
//    androidTestImplementation(libs.androidx.espresso.core)
//}
//
//publishing {
//    publications {
//        create<MavenPublication>("release") {
//            afterEvaluate {
//                from(components["release"])
//                groupId = project.findProperty("GROUP") as String
//                artifactId = project.findProperty("ARTIFACT_ID") as String
//                version = project.findProperty("VERSION_NAME") as String
//            }
//        }
//    }
//    repositories {
//        mavenLocal()
//    }
//}