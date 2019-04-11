package ai.labs.staticresources.rest.impl;

import ai.labs.runtime.SystemRuntime;
import ai.labs.staticresources.rest.IResourceFileManager;
import ai.labs.staticresources.rest.IRestHtmlApiResource;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
public class RestHtmlApiResource implements IRestHtmlApiResource {
    private final IResourceFileManager resourceFileManager;
    private final SystemRuntime.IRuntime runtime;

    @Inject
    public RestHtmlApiResource(IResourceFileManager resourceFileManager, SystemRuntime.IRuntime runtime) {
        this.resourceFileManager = resourceFileManager;
        this.runtime = runtime;
    }

    @Override
    public String viewDefault() {
        return viewHtml("/");
    }

    @Override
    public String viewHtml(String path) {
        try {
            path = preparePath(path);
            return resourceFileManager.getResourceAsString("html", path);
        } catch (IResourceFileManager.NotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    private static String preparePath(String path) {
        if (path.equals("") || path.equals("/")) {
            return "view.html";
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        if (!path.endsWith("/")) {
            path = path + ".html";
        }

        return path;
    }
}
