package ai.labs.staticresources.impl;

import ai.labs.staticresources.rest.IResourceFileManager;
import ai.labs.utilities.FileUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * @author ginccc
 */
@Slf4j
public class ResourceFileManager implements IResourceFileManager {
    private final String resourceDir;

    @Inject
    public ResourceFileManager(@Named("systemRuntime.resourceDir") String resourceDir) {
        this.resourceDir = resourceDir;
    }

    @Override
    public String getResourceAsString(String... paths) throws IResourceFileManager.NotFoundException {
        try {
            Path path = createPath(paths);
            return FileUtilities.readTextFromFile(path.toFile());
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new IResourceFileManager.NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public InputStream getResourceAsInputStream(String... paths) throws IResourceFileManager.NotFoundException {
        Path path = createPath(paths);
        try {
            return Files.newInputStream(path);
        } catch (IOException e) {
            log.error(e.getLocalizedMessage(), e);
            throw new IResourceFileManager.NotFoundException(e.getLocalizedMessage(), e);
        }
    }

    private Path createPath(String[] paths) {
        return Paths.get(FileUtilities.buildPath(System.getProperty("user.dir"), resourceDir), paths);
    }
}
