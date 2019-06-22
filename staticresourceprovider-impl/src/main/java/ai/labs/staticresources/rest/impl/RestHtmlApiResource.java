package ai.labs.staticresources.rest.impl;

import ai.labs.staticresources.rest.IResourceFileManager;
import ai.labs.staticresources.rest.IRestHtmlApiResource;
import org.jboss.resteasy.spi.NoLogWebApplicationException;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.core.Response;

/**
 * @author ginccc
 */
public class RestHtmlApiResource implements IRestHtmlApiResource {
    private static final String KEYCLOAK_SCRIPT_INSERT = "<!-- KEYCLOAK-SCRIPT-INSERT-IF-ENABLED -->";
    private final IResourceFileManager resourceFileManager;
    private final String securityHandleType;
    private final String authServerUrl;

    @Inject
    public RestHtmlApiResource(IResourceFileManager resourceFileManager,
                               @Named("webServer.securityHandlerType") String securityHandleType,
                               @Named("webserver.keycloak.authServerUrl") String authServerUrl) {
        this.resourceFileManager = resourceFileManager;
        this.securityHandleType = securityHandleType;
        this.authServerUrl = authServerUrl;
    }

    @Override
    public String viewDefault() {
        return viewHtml("/");
    }

    @Override
    public String viewHtml(String path) {
        try {
            path = preparePath(path);
            String htmlContent = resourceFileManager.getResourceAsString("html", path);
            if ("keycloak".equals(securityHandleType) && htmlContent.contains(KEYCLOAK_SCRIPT_INSERT)) {
                htmlContent = htmlContent.replaceAll(KEYCLOAK_SCRIPT_INSERT,
                        "<script src=\"" + authServerUrl + "/js/keycloak.js\"></script>");
            }
            return htmlContent;
        } catch (IResourceFileManager.NotFoundException e) {
            throw new NoLogWebApplicationException(Response.Status.NOT_FOUND);
        }
    }

    private static String preparePath(String path) {
        if (path.equals("") || path.equals("/")) {
            return "index.html";
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
