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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestManagerResource implements IRestManagerResource {
    private static final Logger LOGGER = Logger.getLogger(RestManagerResource.class);
    private static final String SPA_CLIENT_ID = "eddi-frontend";

    /** Classpath prefix the Manager's static assets live under. */
    private static final String RESOURCE_BASE = "META-INF/resources";

    /**
     * Characters a Manager asset name may never contain.
     * <p>
     * {@code '\'} is in the set for a security reason, not a cosmetic one. A
     * classpath resource name is '/'-separated, so {@link #normalizeSlashPath}
     * splits on '/' alone and a backslash therefore stays inside a single segment —
     * {@code ..\..\..\pom.xml} pops nothing and would be handed to the classloader
     * intact. The previous {@code Paths.get(...).normalize()} guard did catch that
     * on Windows (where '\' is a separator) and not on Linux; rejecting the
     * character outright is the same answer on every operating system, which is
     * what this guard is supposed to give.
     */
    private static final String INVALID_PATH_CHARS = "<>|:*?\"\\\0";

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
            // Character check FIRST. It used to run after Paths.get, which on Windows
            // throws InvalidPathException (an IllegalArgumentException, so neither catch
            // below matched) for exactly the characters this loop is here to reject —
            // "/manage/a:b" answered 500 instead of the intended 403.
            // (No regex: CodeQL java/polynomial-redos.)
            for (char c : path.toCharArray()) {
                if (INVALID_PATH_CHARS.indexOf(c) >= 0) {
                    throw new SecurityException("Invalid characters in file path");
                }
            }

            // A classpath resource name is '/'-separated, always — it is not a
            // filesystem path. Paths.get(...).toString() rendered it with the platform
            // separator, so on Windows every lookup became "META-INF\resources\..."
            // which no classloader resolves: every existing file under /manage fell
            // through to manage.html, and the traversal guard's behaviour differed by
            // operating system.
            String resourcePath = RESOURCE_BASE + "/" + normalizeSlashPath(path);

            // Attempt to load the file from the resources folder
            InputStream fileStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resourcePath);

            // If the file doesn't exist, fallback to "manage.html"
            if (fileStream == null) {
                fileStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE_BASE + "/manage.html");

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

    /**
     * Collapse {@code .} and {@code ..} segments of a '/'-separated classpath
     * suffix, refusing any path that would climb above the base.
     * <p>
     * Deliberately string-based rather than {@code java.nio.file.Path}: this is a
     * classpath resource name, where the separator is always '/', and NIO also
     * brings platform-specific parsing (and {@code InvalidPathException}) that has
     * no business deciding whether a URL is safe.
     * <p>
     * Splitting on '/' alone is only safe because the caller has already rejected
     * every character in {@link #INVALID_PATH_CHARS}, backslash included — without
     * that, {@code ..\..\x} would survive as one segment that pops nothing.
     */
    private static String normalizeSlashPath(String path) {
        Deque<String> segments = new ArrayDeque<>();
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (segments.isEmpty()) {
                    throw new SecurityException("Directory traversal attempt detected");
                }
                segments.removeLast();
                continue;
            }
            segments.addLast(segment);
        }
        return String.join("/", segments);
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
            int end = realm.length();
            int slashIdx = realm.indexOf('/');
            int qIdx = realm.indexOf('?');
            int hashIdx = realm.indexOf('#');
            if (slashIdx >= 0) {
                end = Math.min(end, slashIdx);
            }
            if (qIdx >= 0) {
                end = Math.min(end, qIdx);
            }
            if (hashIdx >= 0) {
                end = Math.min(end, hashIdx);
            }
            return end > 0 ? realm.substring(0, end) : "eddi";
        }
        return "eddi";
    }

    private static String escapeJs(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
