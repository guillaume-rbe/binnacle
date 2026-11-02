package app.binnacle.gradle;

import com.android.build.api.artifact.ArtifactTransformationRequest;
import com.android.build.api.artifact.ScopedArtifact;
import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.variant.ScopedArtifacts;
import com.android.build.api.variant.ApplicationAndroidComponentsExtension;
import java.io.File;
import java.util.stream.Collectors;
import kotlin.Unit;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.FileCollectionDependency;
import org.gradle.api.file.Directory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;

/**
 * Hooks a module's read-only guards into its Gradle build at the same points build.sh runs them:
 *   before anything compiles   no iCloud conflict copies, tools/check_readonly.sh, tests/run.sh (if any)
 *   after javac, before d8     tools/check_bytecode.sh on the module's classes (javac's and R)
 *   after packing              tools/check_bytecode.sh --apk on the APK (no checked APK, no output)
 * and refuses any library on the runtime classpath, as nothing but the checked classes may be dexed.
 * The APK step also depends on every other check, so even if AGP renames a task that the early
 * hooks below look for, no APK comes out unchecked. Any failure stops the build.
 * Only release APKs are built (as with build.sh): a debug build splits the code over several dex
 * files, which the APK check refuses, and app bundles are refused because nothing checks them.
 */
public final class Guards {
    private Guards() {}

    public static void wire(Project p) {
        File dir = p.getProjectDir();
        TaskProvider<GuardScript> readonly = p.getTasks().register("checkReadonly", GuardScript.class, t -> {
            t.getScript().set(p.file("tools/check_readonly.sh"));
            t.getChecked().from("src", "res", "AndroidManifest.xml", "tools");
            if (new File(dir, "assets").isDirectory()) t.getChecked().from("assets");
            t.getStamp().set(p.getLayout().getBuildDirectory().file("guards/check_readonly.stamp"));
        });
        TaskProvider<GuardScript> tests = new File(dir, "tests/run.sh").isFile()
            ? p.getTasks().register("plainTests", GuardScript.class, t -> {
                t.getScript().set(p.file("tests/run.sh"));
                t.getChecked().from("src", "tests");
                t.getStamp().set(p.getLayout().getBuildDirectory().file("guards/tests.stamp"));
            })
            : null;
        TaskProvider<NoICloudCopies> icloud = p.getTasks().register("checkNoICloudCopies", NoICloudCopies.class, t -> {
            t.getRoots().from("src", "res", "assets");
            t.getStamp().set(p.getLayout().getBuildDirectory().file("guards/icloud.stamp"));
        });
        p.getTasks().matching(t -> t.getName().equals("preBuild")).configureEach(t -> {
            t.dependsOn(icloud, readonly);
            if (tests != null) t.dependsOn(tests);
        });

        ApplicationAndroidComponentsExtension android =
            p.getExtensions().getByType(ApplicationAndroidComponentsExtension.class);
        android.beforeVariants(android.selector().withBuildType("debug"), v -> {
            v.setEnable(false);
            return Unit.INSTANCE;
        });
        android.onVariants(android.selector().all(), variant -> {
            String v = variant.getName().substring(0, 1).toUpperCase() + variant.getName().substring(1);
            // Every class that d8 will see: javac's output, the R classes, and (scope ALL) anything a
            // library would bring, so nothing reaches the dex without this check.
            TaskProvider<CollectClasses> collect = p.getTasks().register("collectClasses" + v, CollectClasses.class,
                t -> t.getClasses().set(p.getLayout().getBuildDirectory().dir("guards/classes_" + variant.getName())));
            variant.getArtifacts().forScope(ScopedArtifacts.Scope.ALL).use(collect)
                .toGet(ScopedArtifact.CLASSES.INSTANCE, CollectClasses::getJars, CollectClasses::getDirs);
            TaskProvider<GuardScript> classes = p.getTasks().register("checkBytecode" + v, GuardScript.class, t -> {
                Provider<Directory> out = collect.flatMap(CollectClasses::getClasses);
                t.getScript().set(p.file("tools/check_bytecode.sh"));
                // relative to the module: the script word-splits the path (it has spaces under iCloud)
                t.getArgs().add(out.map(d -> dir.toPath().relativize(d.getAsFile().toPath()).toString()));
                t.getChecked().from(out, "tools");
                t.getStamp().set(p.getLayout().getBuildDirectory().file("guards/check_bytecode_" + variant.getName() + ".stamp"));
            });
            // And no library at all: modules and jars from a repository, or local jars (files(...),
            // which the dependency graph leaves out).
            Configuration runtime = variant.getRuntimeConfiguration();
            TaskProvider<NoDependencies> libs = p.getTasks().register("checkNoLibraries" + v, NoDependencies.class, t -> {
                t.getDependencies().addAll(runtime.getIncoming().getResolutionResult().getRootComponent()
                    .map(root -> root.getDependencies().stream().map(Object::toString).collect(Collectors.toList())));
                t.getDependencies().addAll(p.provider(() -> runtime.getAllDependencies().withType(FileCollectionDependency.class)
                    .stream().flatMap(d -> d.getFiles().getFiles().stream()).map(File::getName).collect(Collectors.toList())));
            });
            p.getTasks().matching(t -> t.getName().equals("dexBuilder" + v)).configureEach(t -> t.dependsOn(classes, libs));
            // The packed APK: one dex, no native code, nothing hidden. Only a checked APK comes out.
            TaskProvider<CheckedApk> apk = p.getTasks().register("checkApk" + v, CheckedApk.class,
                t -> t.getScript().set(p.file("tools/check_bytecode.sh")));
            ArtifactTransformationRequest<CheckedApk> request = variant.getArtifacts().use(apk)
                .wiredWithDirectories(CheckedApk::getPacked, CheckedApk::getChecked)
                .toTransformMany(SingleArtifact.APK.INSTANCE);
            apk.configure(t -> {
                t.getRequest().set(request);
                t.dependsOn(icloud, readonly, classes, libs);
                if (tests != null) t.dependsOn(tests);
            });
            p.getTasks().matching(t -> t.getName().equals("bundle" + v)).configureEach(t -> t.doFirst(x -> {
                throw new GradleException("app bundles aren't checked by the read-only guards: build the APK");
            }));
            return Unit.INSTANCE;
        });
    }
}
