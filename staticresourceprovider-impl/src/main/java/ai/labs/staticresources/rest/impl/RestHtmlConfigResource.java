package ai.labs.staticresources.rest.impl;

import ai.labs.staticresources.rest.IResourceFileManager;
import ai.labs.staticresources.rest.IRestHtmlConfigResource;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
public class RestHtmlConfigResource implements IRestHtmlConfigResource {
    private final IResourceFileManager resourceFileManager;

    @Inject
    public RestHtmlConfigResource(IResourceFileManager resourceFileManager) {
        this.resourceFileManager = resourceFileManager;
    }

    @Override
    public String viewDefault() {
        return viewHtml("/");
    }

    @Override
    public String viewHtml(String path) {
        try {
            return resourceFileManager.getResourceAsString("html", "config.html");
        } catch (IResourceFileManager.NotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }
}
