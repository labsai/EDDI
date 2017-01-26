package ai.labs.staticresources.rest.impl;

import ai.labs.staticresources.IResourceFilesManager;
import ai.labs.staticresources.rest.IRestTextResource;
import ai.labs.utilities.FileUtilities;

import javax.inject.Inject;
import java.io.File;

/**
 * @author ginccc
 */
public class RestTextResource implements IRestTextResource {

    private final IResourceFilesManager resourceFilesManager;

    @Inject
    public RestTextResource(IResourceFilesManager resourceFilesManager) {
        this.resourceFilesManager = resourceFilesManager;
    }

    @Override
    public File getStaticResource(String path) {
        String baseWebPath = resourceFilesManager.getOptions().getBaseWebPath();
        return new File(FileUtilities.buildPath(baseWebPath, path));
    }
}
