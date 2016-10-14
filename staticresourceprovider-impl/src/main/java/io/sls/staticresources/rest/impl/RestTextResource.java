package io.sls.staticresources.rest.impl;

import io.sls.staticresources.IResourceFilesManager;
import io.sls.staticresources.rest.IRestTextResource;
import io.sls.utilities.FileUtilities;

import javax.inject.Inject;
import java.io.File;

/**
 * User: jarisch
 * Date: 07.08.12
 * Time: 16:04
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
