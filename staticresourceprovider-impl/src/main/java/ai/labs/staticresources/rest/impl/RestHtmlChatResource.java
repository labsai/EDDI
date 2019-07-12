package ai.labs.staticresources.rest.impl;

import ai.labs.staticresources.rest.IResourceFileManager;
import ai.labs.staticresources.rest.IRestHtmlChatResource;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
@RequestScoped
public class RestHtmlChatResource implements IRestHtmlChatResource {
    private final IResourceFileManager resourceFileManager;

    @Inject
    public RestHtmlChatResource(IResourceFileManager resourceFileManager) {
        this.resourceFileManager = resourceFileManager;
    }

    @Override
    public String viewDefault() {
        return viewHtml("/");
    }

    @Override
    public String viewHtml(String path) {
        try {
            return resourceFileManager.getResourceAsString("html", "chat.html");
        } catch (IResourceFileManager.NotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

}
