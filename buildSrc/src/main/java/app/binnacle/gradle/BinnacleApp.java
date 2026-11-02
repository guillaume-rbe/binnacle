package app.binnacle.gradle;

import com.android.build.api.dsl.ApplicationBuildType;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.dsl.AndroidSourceSet;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.gradle.api.GradleException;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/**
 * The settings the dash and the logger share, so the two can't drift apart (they must be signed
 * with the same key: they share a signature permission). Each module's build.gradle.kts applies
 * the Android plugin, calls this, and adds only what is its own.
 *
 * Everything here mirrors app/build.sh and capture/build.sh, which build from the same folders:
 * src/, res/, assets/ and AndroidManifest.xml in the module folder, Java 8 from JDK 17's javac,
 * minSdk 21, targetSdk 29, the latest platform, the debug keystore unless releasing.
 */
public final class BinnacleApp {
    private BinnacleApp() {}

    public static void configure(Project p, String namespace) {
        // build.sh owns build/ (it starts with rm -rf build), so Gradle keeps to a folder of its own.
        p.getLayout().getBuildDirectory().set(p.file("build/gradle"));

        ApplicationExtension android = p.getExtensions().getByType(ApplicationExtension.class);
        android.setNamespace(namespace);
        android.setCompileSdk(37);
        android.getDefaultConfig().setApplicationId(namespace);
        android.getDefaultConfig().setMinSdk(21);
        android.getDefaultConfig().setTargetSdk(29);
        // AndroidManifest.xml stays the one place for the version, as build.sh reads it from there.
        String manifest = read(p.file("AndroidManifest.xml"));
        android.getDefaultConfig().setVersionCode(Integer.parseInt(attr(manifest, "versionCode")));
        android.getDefaultConfig().setVersionName(attr(manifest, "versionName"));

        AndroidSourceSet main = android.getSourceSets().getByName("main");
        main.getManifest().srcFile("AndroidManifest.xml");
        main.getJava().getDirectories().add("src");
        main.getRes().getDirectories().add("res");
        if (p.file("assets").isDirectory()) main.getAssets().getDirectories().add("assets");

        ApplicationBuildType release = android.getBuildTypes().getByName("release");
        // ./release.sh passes -Pbinnacle.unsigned and signs with the release key itself, so
        // apksigner asks for the password and nothing stores it.
        release.setSigningConfig(p.getProviders().gradleProperty("binnacle.unsigned").isPresent()
            ? null : android.getSigningConfigs().getByName("debug"));
        release.getVcsInfo().setInclude(false);    // no git details packed into the APK, as with build.sh

        android.getCompileOptions().setSourceCompatibility(JavaVersion.VERSION_1_8);
        android.getCompileOptions().setTargetCompatibility(JavaVersion.VERSION_1_8);
        // build.sh never ran lint; targetSdk 29 is deliberate (the unit is Android 10).
        android.getLint().setCheckReleaseBuilds(false);
        android.getDependenciesInfo().setIncludeInApk(false);
        android.getDependenciesInfo().setIncludeInBundle(false);
        // javac from JDK 17, as build.sh uses (and javap in check_bytecode.sh reads its output).
        p.getExtensions().getByType(JavaPluginExtension.class).getToolchain()
            .getLanguageVersion().set(JavaLanguageVersion.of(17));

        Guards.wire(p);
    }

    private static String read(File f) {
        try {
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new GradleException("can't read " + f, e);
        }
    }

    private static String attr(String manifest, String name) {
        Matcher m = Pattern.compile("android:" + name + "=\"([^\"]*)\"").matcher(manifest);
        if (!m.find()) throw new GradleException("AndroidManifest.xml has no android:" + name);
        return m.group(1);
    }
}
