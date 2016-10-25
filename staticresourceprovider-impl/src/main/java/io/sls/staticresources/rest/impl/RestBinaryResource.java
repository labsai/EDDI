package io.sls.staticresources.rest.impl;

import io.sls.staticresources.IResourceFilesManager;
import io.sls.staticresources.rest.IRestBinaryResource;
import io.sls.utilities.FileUtilities;

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
