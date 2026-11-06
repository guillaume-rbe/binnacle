plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.shilapi.xcertplay"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.shilapi.xcertplay"
        minSdk = 28
        targetSdk = 37
        versionCode = 1404
        versionName = "1.4.2"
        testInstrumentationRunner = "com.shilapi.xcertplay.CarPlayMediaServiceLifecycleTest"
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                providers.environmentVariable("ANDROID_KEYSTORE_PATH")
                    .getOrElse("missing-release-keystore.jks"),
            )
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").getOrElse("")
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").getOrElse("")
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").getOrElse("")
        }
    }

    buildTypes {
        debug {
            if (providers.environmentVariable("ANDROID_KEYSTORE_PATH").isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            optimization {
                enable = false
            }
            // Binnacle's release.sh builds this unsigned (-Pxcertplay.unsigned=true) and signs it
            // with the dash's release key itself, like the dash.
            signingConfig = if (providers.gradleProperty("xcertplay.unsigned").isPresent) {
                null
            } else {
                signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(project(":common"))
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.app.projected)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Binnacle's CarPlay guard (carplay/guard/check_carplay.sh): nothing to the car, and the CAN app
// gets only the approved call-state broadcast. Runs before every build of this app and stops it on
// any failure. Never weaken or bypass it.
val checkCarPlayGuard = tasks.register<Exec>("checkCarPlayGuard") {
    workingDir(rootDir)
    commandLine("bash", "guard/check_carplay.sh")
}
tasks.named("preBuild") {
    dependsOn(checkCarPlayGuard)
}
