package app.binnacle.gradle;

import com.android.build.api.artifact.ArtifactTransformationRequest;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/**
 * Sits between packaging and the final APK (an AGP transform of the APK artifact): runs
 * tools/check_bytecode.sh --apk on every packed APK and only then copies it to the output. If the
 * check fails there is no final APK to install, as with build.sh.
 */
public abstract class CheckedApk extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getScript();

    @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getPacked();

    @OutputDirectory
    public abstract DirectoryProperty getChecked();

    @Internal
    public abstract Property<ArtifactTransformationRequest<CheckedApk>> getRequest();

    @Inject
    protected abstract ExecOperations getExec();

    @TaskAction
    public void run() {
        String script = getScript().get().getAsFile().getAbsolutePath();
        File out = getChecked().get().getAsFile();
        getRequest().get().submit(this, artifact -> {
            File apk = new File(artifact.getOutputFile());
            getExec().exec(spec -> spec.commandLine("bash", script, "--apk", apk.getAbsolutePath()));
            File checked = new File(out, apk.getName());
            try {
                Files.copy(apk.toPath(), checked.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (java.io.IOException e) {
                throw new GradleException("can't copy " + apk, e);
            }
            return checked;
        });
    }
}
