import app.binnacle.build.Guards

// Builds the same APK as capture/build.sh (the drive logger), held to the logger's own guards:
//   ./gradlew :logger:assembleRelease   -> capture/build/gradle/outputs/apk/release/logger-release.apk
// It must be signed with the same key as the dash (they share a signature permission):
// the debug keystore here, the release key through ./release.sh.
plugins {
    id("com.android.application")
}

// build.sh owns build/ (it starts with rm -rf build), so Gradle keeps to a folder of its own.
layout.buildDirectory = file("build/gradle")

// AndroidManifest.xml stays the one place for the version, as build.sh reads it from there.
val manifestText = file("AndroidManifest.xml").readText()
fun manifestAttr(name: String) =
    Regex("""android:$name="([^"]*)"""").find(manifestText)?.groupValues?.get(1)
        ?: error("AndroidManifest.xml has no android:$name")

android {
    namespace = "app.binnacle.logger"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.binnacle.logger"
        minSdk = 21
        targetSdk = 29
        versionCode = manifestAttr("versionCode").toInt()
        versionName = manifestAttr("versionName")
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            java.directories.add("src")
            res.directories.add("res")
        }
    }

    buildTypes {
        release {
            signingConfig = if (providers.gradleProperty("binnacle.unsigned").isPresent) null
                else signingConfigs.getByName("debug")
            vcsInfo.include = false    // no git details packed into the APK, as with build.sh
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    lint {
        checkReleaseBuilds = false
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

Guards.wire(project)
