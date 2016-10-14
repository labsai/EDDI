package io.sls.staticresources.rest.impl;

import io.sls.staticresources.IResourceFilesManager;
import io.sls.staticresources.rest.IRestBinaryResource;
import io.sls.utilities.FileUtilities;

import javax.inject.Inject;
import java.io.File;

/**
 * User: jarisch
 * Date: 08.08.12
 * Time: 18:07
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
