package ai.labs.staticresources.rest.impl;

import ai.labs.staticresources.IResourceFilesManager;
import ai.labs.staticresources.rest.IRestBinaryResource;
import ai.labs.utilities.FileUtilities;

import javax.inject.Inject;
import java.io.File;

/**
 * @author ginccc
 */
public class RestBinaryResource implements IRestBinaryResource {

    private final IResourceFilesManager resourceFilesManager;

    @Inject
    public RestBinaryResource(IResourceFilesManager resourceFilesManager) {
        this.resourceFilesManager = resourceFilesManager;
    }

    @Override
    public File getBinary(String path) {
        String baseWebPath = resourceFilesManager.getOptions().getBaseWebPath();
        return new File(FileUtilities.buildPath(baseWebPath, path));
    }
}
