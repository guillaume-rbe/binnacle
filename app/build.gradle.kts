import app.binnacle.gradle.BinnacleApp

// Builds the same APK as app/build.sh, from the same folders, held to the same guards:
//   ./gradlew :app:assembleRelease      -> app/build/gradle/outputs/apk/release/app-release.apk
// Signed with ~/.android/debug.keystore like build.sh; ./release.sh signs with the release key.
// Shared settings and the guards: buildSrc/src/main/java/app/binnacle/gradle/.
plugins {
    id("com.android.application")
}

BinnacleApp.configure(project, "app.binnacle")

android {
    defaultConfig {
        // Installed as com.xbs.launcher so the stock CAN app hides its door pop-up over the dash
        // (see com.xbs.launcher.MainActivity in the manifest). The Java code stays in app.binnacle.
        // -Pbinnacle.appId=app.binnacle (or APP_ID=app.binnacle) builds the side-by-side copy.
        applicationId = providers.gradleProperty("binnacle.appId")
            .orElse(providers.environmentVariable("APP_ID"))
            .getOrElse("com.xbs.launcher")
    }
}
