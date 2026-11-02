package app.binnacle.gradle;

import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

/**
 * Fails if anything but the module's own source would go into the APK. The guards read what javac
 * made from src/; a library (even one a plugin adds by default, such as AGP 9's Kotlin standard
 * library) would reach the dex without being checked. build.sh can't add one, so neither may Gradle.
 */
public abstract class NoDependencies extends DefaultTask {
    /** The variant's resolved runtime dependencies. */
    @Input
    public abstract ListProperty<String> getDependencies();

    @TaskAction
    public void run() {
        List<String> deps = getDependencies().get();
        if (!deps.isEmpty()) {
            throw new GradleException("libraries would be packed into the APK unchecked by the read-only guards: " + deps);
        }
    }
}
