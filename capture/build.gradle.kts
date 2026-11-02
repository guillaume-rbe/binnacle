import app.binnacle.gradle.BinnacleApp

// Builds the same APK as capture/build.sh (the drive logger), held to the logger's own guards:
//   ./gradlew :logger:assembleRelease   -> capture/build/gradle/outputs/apk/release/logger-release.apk
// Signed with the same key as the dash (they share a signature permission): the debug keystore
// here, the release key through ./release.sh. Shared settings: buildSrc/src/main/java/app/binnacle/gradle/.
plugins {
    id("com.android.application")
}

BinnacleApp.configure(project, "app.binnacle.logger")
