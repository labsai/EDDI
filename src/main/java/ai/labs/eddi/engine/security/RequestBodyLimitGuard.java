/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.runtime.configuration.MemorySize;
import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
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
 * {@code Content-Length} above {@code eddi.http.limits.default-max-body-size}
 * is refused with 413 before its body is read, unless it is an upload to a
 * knowledge base's file source, which keeps the global ceiling.
 *
 * <p>
 * A chunked request declares no length and so cannot be judged here; the global
 * ceiling still bounds it.
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

    @ConfigProperty(name = "eddi.http.limits.default-max-body-size", defaultValue = "25M")
    MemorySize defaultMaxBodySize;

    void register(@Observes Filters filters) {
        filters.register(this::handle, PRIORITY);
    }

    void handle(RoutingContext context) {
        long declared = declaredLength(context.request().getHeader(HttpHeaders.CONTENT_LENGTH));
        if (declared > defaultMaxBodySize.asLongValue() && !mayCarryLargeBody(context)) {
            context.response()
                    .setStatusCode(413)
                    .putHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .end("{\"error\":\"The request body is larger than this endpoint accepts ("
                            + defaultMaxBodySize.asLongValue() / (1024 * 1024) + " MB).\"}");
            return;
        }
        context.next();
    }

    private static boolean mayCarryLargeBody(RoutingContext context) {
        String path = context.normalizedPath();
        return "POST".equals(context.request().method().name()) && path != null
                && LARGE_BODY_PATHS.matcher(path).matches();
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
