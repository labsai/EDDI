package ai.labs.staticresources.rest.impl;

import ai.labs.staticresources.rest.IContentTypeProvider;
import ai.labs.staticresources.rest.IResourceFileManager;
import ai.labs.staticresources.rest.IRestBinaryResource;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.Response;
import java.io.InputStream;

/**
 * @author ginccc
 */
public class RestBinaryResource implements IRestBinaryResource {
    private final IResourceFileManager resourceFileManager;
    private final IContentTypeProvider contentTypeProvider;

    @Inject
    public RestBinaryResource(IResourceFileManager resourceFileManager, IContentTypeProvider contentTypeProvider) {
        this.resourceFileManager = resourceFileManager;
        this.contentTypeProvider = contentTypeProvider;
    }

    @Override
    public Response getBinary(String path) {
        try {
            String extension = path.contains(".") ? path.substring(path.lastIndexOf(".") + 1) : null;
            InputStream fileStream = resourceFileManager.getResourceAsInputStream(path);
            if (fileStream != null) {
                return Response.ok(fileStream).type(contentTypeProvider.getContentTypeByExtension(extension)).build();
            } else {
                throw new NotFoundException();
            }
        } catch (IResourceFileManager.NotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }
}
