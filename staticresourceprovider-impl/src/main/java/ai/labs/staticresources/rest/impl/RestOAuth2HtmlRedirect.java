package ai.labs.staticresources.rest.impl;

import ai.labs.staticresources.rest.IResourceFileManager;
import ai.labs.staticresources.rest.IRestOAuth2HtmlRedirect;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.core.Response;

public class RestOAuth2HtmlRedirect implements IRestOAuth2HtmlRedirect {
    private final IResourceFileManager resourceFileManager;

    @Inject
    public RestOAuth2HtmlRedirect(IResourceFileManager resourceFileManager) {
        this.resourceFileManager = resourceFileManager;
    }

    @Override
    public String viewHtmlRedirect() {
        try {
            return resourceFileManager.getResourceAsString("html", "oauth2-redirect.html");
        } catch (IResourceFileManager.NotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }
}
