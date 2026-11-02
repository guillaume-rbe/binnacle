package app.binnacle.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/**
 * Runs one of the module's own guard scripts (tools/check_readonly.sh, tools/check_bytecode.sh,
 * tests/run.sh) exactly as build.sh does, so Gradle and build.sh are held to the same checks.
 * A non-zero exit fails the build. The stamp file only lets Gradle skip a rerun when nothing the
 * script reads has changed.
 */
public abstract class GuardScript extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getScript();

    @Input
    public abstract ListProperty<String> getArgs();

    /** Everything the script reads: when any of it changes, the script runs again. */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getChecked();

    /** The SDK AGP found (ANDROID_HOME or local.properties' sdk.dir), passed on as ANDROID_HOME. */
    @Input
    public abstract Property<String> getAndroidHome();

    @OutputFile
    public abstract RegularFileProperty getStamp();

    @Inject
    protected abstract ExecOperations getExec();

    @TaskAction
    public void run() throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add("bash");
        cmd.add(getScript().get().getAsFile().getAbsolutePath());
        cmd.addAll(getArgs().get());
        getExec().exec(spec -> {
            spec.commandLine(cmd);
            spec.environment("ANDROID_HOME", getAndroidHome().get());
        });
        Files.write(getStamp().get().getAsFile().toPath(), (String.join(" ", cmd) + "\n").getBytes("UTF-8"));
    }
}
