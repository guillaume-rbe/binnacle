import app.binnacle.build.Guards

// Builds the same APK as app/build.sh, from the same folders, held to the same guards:
//   ./gradlew :app:assembleRelease      -> app/build/gradle/outputs/apk/release/app-release.apk
// Signed with ~/.android/debug.keystore like build.sh; ./release.sh signs with the release key.
// -Pbinnacle.appId=app.binnacle (or APP_ID=app.binnacle) builds the side-by-side copy.
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
    namespace = "app.binnacle"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Installed as com.xbs.launcher so the stock CAN app hides its door pop-up over the dash
        // (see com.xbs.launcher.MainActivity in the manifest). The Java code stays in app.binnacle.
        applicationId = providers.gradleProperty("binnacle.appId")
            .orElse(providers.environmentVariable("APP_ID"))
            .getOrElse("com.xbs.launcher")
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
            assets.directories.add("assets")
        }
    }

    buildTypes {
        release {
            // ./release.sh passes -Pbinnacle.unsigned and signs with the release key itself, so
            // apksigner asks for the password and nothing stores it.
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
        // build.sh never ran lint; targetSdk 29 is deliberate (the unit is Android 10).
        checkReleaseBuilds = false
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

// javac from JDK 17, as build.sh uses (and javap in check_bytecode.sh reads its output).
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

// The read-only guards and tests, run where build.sh runs them (buildSrc/.../Guards.java).
Guards.wire(project)
