/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.regex.Pattern;

/**
 * Holds every request body to the old 25 MB limit except the one endpoint that
 * needs more.
 *
 * <p>
 * {@code quarkus.http.limits.max-body-size} was raised to 60 MB so a knowledge
 * base's file upload can carry a file at its configured limit — and, being
 * global, that raised it for every endpoint: any authenticated JSON endpoint
 * now accepted, buffered and parsed 60 MB bodies. Quarkus has no per-route body
 * limit, so this filter restores one: a request that declares a
 * {@code Content-Length} above the effective limit is refused with 413 before
 * its body is read, unless it is an upload to a knowledge base's file source,
 * which keeps the global ceiling.
 *
 * <h2>The effective limit</h2>
 * <p>
 * {@code eddi.http.limits.default-max-body-size}, raised if need be to what an
 * attachment at {@code eddi.attachments.max-size-bytes} takes once it is
 * base64-encoded into a JSON body (four thirds, plus a megabyte for the rest of
 * the message) — so an operator raising the attachment limit, as its
 * validator's message tells them to, is not then refused here with a bare 413.
 *
 * <h2>Bodies without a length</h2>
 * <p>
 * A chunked request, or an HTTP/2 request that sends data without a
 * {@code content-length}, declares nothing to judge. Counting its bytes as they
 * arrive is not possible from here: the REST layer installs its own handler on
 * the request later and would replace a counting one. So such a body is refused
 * with 411 on every endpoint but the upload, before any of it is read — JSON
 * clients and browsers send the length. {@code
 * eddi.http.limits.refuse-unsized-bodies=false} turns that off for a client
 * that cannot, leaving those bodies to the global ceiling.
 *
 * <p>
 * Every refusal closes the connection, so the server does not go on draining a
 * body it has already refused.
 */
@ApplicationScoped
public class RequestBodyLimitGuard {

    /** Below {@link HttpMethodGuard}, which refuses whole methods first. */
    static final int PRIORITY = 19_000;

    /**
     * {@code POST /ragstore/rags/{id}/sources/{sourceId}/files} — the only endpoint
     * whose bodies are meant to exceed the default.
     */
    static final Pattern LARGE_BODY_PATHS = Pattern.compile("^/ragstore/rags/[^/]+/sources/[^/]+/files/?$");

    /** Room for the rest of a JSON message around a base64-encoded attachment. */
    private static final long ENVELOPE_BYTES = 1024L * 1024;

    @ConfigProperty(name = "eddi.http.limits.default-max-body-size", defaultValue = "25M")
    MemorySize defaultMaxBodySize;

    @ConfigProperty(name = "eddi.attachments.max-size-bytes", defaultValue = "20971520")
    long attachmentMaxBytes = 20_971_520L;

    @ConfigProperty(name = "eddi.http.limits.refuse-unsized-bodies", defaultValue = "true")
    boolean refuseUnsizedBodies = true;

    /** Paths are matched below it, so the exemption holds under a prefix. */
    @ConfigProperty(name = "quarkus.http.root-path", defaultValue = "/")
    String rootPath = "/";

    void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext context) {
        if (mayCarryLargeBody(context)) {
            context.next();
            return;
        }
        HttpServerRequest request = context.request();
        long declared = declaredLength(request.getHeader(HttpHeaders.CONTENT_LENGTH));
        long limit = effectiveLimit();
        if (declared > limit) {
            refuse(context, 413, "The request body is larger than this endpoint accepts ("
                    + limit / (1024 * 1024) + " MB).");
            return;
        }
        if (declared < 0 && refuseUnsizedBodies && carriesUnsizedBody(request)) {
            refuse(context, 411, "This endpoint needs the request's Content-Length.");
            return;
        }
        context.next();
    }

    /**
     * The configured limit, or what the largest allowed attachment needs, whichever
     * is more.
     */
    long effectiveLimit() {
        long attachment = attachmentMaxBytes <= 0 ? 0 : (attachmentMaxBytes / 3 + 1) * 4 + ENVELOPE_BYTES;
        return Math.max(defaultMaxBodySize.asLongValue(), attachment);
    }

    /**
     * A body is on its way without a declared length: chunked on HTTP/1.1, or data
     * still to come on HTTP/2, where a request with no body ends with its headers.
     */
    private static boolean carriesUnsizedBody(HttpServerRequest request) {
        if (request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
            return true;
        }
        HttpVersion version = request.version();
        return version != null && version != HttpVersion.HTTP_1_0 && version != HttpVersion.HTTP_1_1
                && !request.isEnded();
    }

    private static void refuse(RoutingContext context, int status, String message) {
        context.response()
                .setStatusCode(status)
                .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .putHeader(HttpHeaders.CONNECTION, "close")
                .end("{\"error\":\"" + message + "\"}");
    }

    private boolean mayCarryLargeBody(RoutingContext context) {
        String path = relativeToRoot(context.normalizedPath());
        return "POST".equals(context.request().method().name()) && path != null
                && LARGE_BODY_PATHS.matcher(path).matches();
    }

    /**
     * The path below {@code quarkus.http.root-path}, or null when it is not under
     * it.
     */
    private String relativeToRoot(String path) {
        if (path == null) {
            return null;
        }
        String root = rootPath == null
                ? "/"
                : rootPath.endsWith("/")
                        ? rootPath.substring(0, rootPath.length() - 1)
                        : rootPath;
        if (root.isEmpty()) {
            return path;
        }
        return path.startsWith(root + "/") ? path.substring(root.length()) : null;
    }

    /** The declared length, or -1 when there is none to judge. */
    private static long declaredLength(String header) {
        if (header == null || header.isBlank()) {
            return -1;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            // Malformed: the HTTP layer refuses it on its own terms.
            return -1;
        }
    }
}
