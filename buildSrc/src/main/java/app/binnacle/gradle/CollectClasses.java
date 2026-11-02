package app.binnacle.gradle;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ArchiveOperations;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.file.RegularFile;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Gathers every class of the module that goes into the dex (javac's output and AGP's generated
 * R classes, which come as a jar) into one folder, so tools/check_bytecode.sh sees all of it, as it
 * does with build.sh (where R.java is compiled with the rest).
 */
public abstract class CollectClasses extends DefaultTask {
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<RegularFile> getJars();

    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ListProperty<Directory> getDirs();

    @OutputDirectory
    public abstract DirectoryProperty getClasses();

    @Inject
    protected abstract FileSystemOperations getFs();

    @Inject
    protected abstract ArchiveOperations getArchives();

    @TaskAction
    public void run() {
        getFs().sync(spec -> {
            spec.into(getClasses());
            for (Directory d : getDirs().get()) spec.from(d);
            for (RegularFile j : getJars().get()) spec.from(getArchives().zipTree(j));
            spec.include("**/*.class");
        });
    }
}
