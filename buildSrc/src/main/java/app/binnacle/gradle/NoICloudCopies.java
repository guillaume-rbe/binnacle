package app.binnacle.gradle;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * iCloud conflict copies ("Name 2.java", "cars 2/") would be compiled or packed in: refuse, as
 * build.sh does (files and folders). Delete them first.
 */
public abstract class NoICloudCopies extends DefaultTask {
    /** The folders to search (src, res, assets). */
    @InputFiles @PathSensitive(PathSensitivity.RELATIVE)
    public abstract ConfigurableFileCollection getRoots();

    @OutputFile
    public abstract RegularFileProperty getStamp();

    @TaskAction
    public void run() throws IOException {
        for (File root : getRoots().getFiles()) {
            if (!root.exists()) continue;
            try (Stream<Path> walk = Files.walk(root.toPath())) {
                List<String> dupes = walk.filter(f -> f.getFileName().toString().matches(".* 2(\\..*)?"))
                    .map(Path::toString).collect(Collectors.toList());
                if (!dupes.isEmpty()) throw new GradleException("iCloud duplicates in the tree (delete them first): " + dupes);
            }
        }
        Files.write(getStamp().get().getAsFile().toPath(), new byte[0]);
    }
}
