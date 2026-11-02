package app.binnacle.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Sits between packing an app bundle and the final .aab (an AGP transform of the bundle
 * artifact) and always fails, so no bundle comes out: nothing checks what a bundle holds.
 * Guards also stops a bundle build before it starts; this is the backstop if AGP renames tasks.
 */
public abstract class RefuseBundle extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getPacked();

    @OutputFile
    public abstract RegularFileProperty getRefused();

    @TaskAction
    public void run() {
        throw new GradleException("app bundles aren't checked by the read-only guards: build the APK");
    }
}
