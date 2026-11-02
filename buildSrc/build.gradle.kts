// The guard tasks shared by the app and the logger, in plain Java. AGP comes in here (not through
// a plugins { } version in the modules) so these tasks and the modules see the same AGP classes.
plugins {
    java
}

dependencies {
    implementation(gradleApi())
    implementation(libs.android.gradle)
}
