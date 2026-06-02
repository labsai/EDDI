/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestManagerResource implements IRestManagerResource {
    private static final Logger LOGGER = Logger.getLogger(RestManagerResource.class);
    private static final String SPA_CLIENT_ID = "eddi-frontend";

    @ConfigProperty(name = "eddi.keycloak.public.url")
    Optional<String> keycloakPublicUrl;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url", defaultValue = "")
    String oidcAuthServerUrl;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @Override
    public Response fetchManagerResources() {
        return fetchManagerResources("/manage.html");
    }

    @Override
    public Response fetchAuthConfig() {
        return Response.ok(buildAuthConfigJs())
                .type("application/javascript")
                .header("Cache-Control", "no-cache, must-revalidate")
                .build();
    }

    @Override
    public Response fetchManagerResources(String path) {
        try {
            // Strip leading "./" or "././" for clarity
            while (path.startsWith("./")) {
                path = path.substring(2);
            }

            // Normalize the path to resolve relative elements
            Path resourcePath = Paths.get("META-INF/resources", path).normalize();

            // Prevent directory traversal: normalized path must stay under the base
            Path basePath = Paths.get("META-INF/resources").normalize();
            if (!resourcePath.startsWith(basePath)) {
                throw new SecurityException("Directory traversal attempt detected");
            }

            // Disallow characters in file names that may be used maliciously
            // (avoids regex to prevent polynomial ReDoS — CodeQL java/polynomial-redos)
            String invalidChars = "<>|:*?\"\0";
            for (char c : path.toCharArray()) {
                if (invalidChars.indexOf(c) >= 0) {
                    throw new SecurityException("Invalid characters in file path");
                }
            }

            // Attempt to load the file from the resources folder
            InputStream fileStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath.toString());

            // If the file doesn't exist, fallback to "manage.html"
            if (fileStream == null) {
                fileStream = Thread.currentThread().getContextClassLoader().getResourceAsStream("META-INF/resources/manage.html");

                if (fileStream == null) {
                    throw new FileNotFoundException("manage.html not found in META-INF/resources");
                }
            }

            // Return the file (or manage.html) as a response
            return Response.ok(fileStream).build();

        } catch (SecurityException e) {
            LOGGER.error("Blocked resource access attempt: " + path, e);
            throw new ForbiddenException("Access to the requested resource is forbidden");
        } catch (IOException e) {
            LOGGER.error("Failed to serve resource: " + path, e);
            throw new InternalServerErrorException("An error occurred while accessing the resource");
        }
    }

    private String buildAuthConfigJs() {
        if (!oidcEnabled) {
            return "window.__EDDI_AUTH__={method:\"none\"};";
        }

        String url = keycloakPublicUrl.filter(s -> !s.isBlank()).orElse("");
        String realm = extractRealm(oidcAuthServerUrl);

        StringBuilder sb = new StringBuilder("window.__EDDI_AUTH__={");
        sb.append("method:\"keycloak\"");
        if (!url.isBlank()) {
            sb.append(",url:\"").append(escapeJs(url)).append("\"");
        }
        sb.append(",realm:\"").append(escapeJs(realm)).append("\"");
        sb.append(",clientId:\"").append(SPA_CLIENT_ID).append("\"");
        sb.append("};");
        return sb.toString();
    }

    private static String extractRealm(String authServerUrl) {
        if (authServerUrl == null || authServerUrl.isBlank()) {
            return "eddi";
        }
        int idx = authServerUrl.lastIndexOf("/realms/");
        if (idx >= 0) {
            String realm = authServerUrl.substring(idx + "/realms/".length());
            int qIdx = realm.indexOf('?');
            return qIdx >= 0 ? realm.substring(0, qIdx) : realm;
        }
        return "eddi";
    }

    private static String escapeJs(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
