/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import java.util.Locale;
import java.util.Set;

/**
 * Refuses {@code TRACE} and {@code TRACK} on every path with 405.
 * <p>
 * Nothing else stopped them: the auth permissions enumerate the methods they
 * guard, so a method no permission names matched no policy and was served —
 * {@code TRACE /q/health} answered 200. TRACE echoes the request back, which is
 * the classic cross-site-tracing primitive for reading headers a script cannot
 * see, and no EDDI endpoint has a use for either method.
 * <p>
 * The verb is read from {@code method().name()}. On Vert.x 4 {@code HttpMethod}
 * is a class, not the Vert.x 3 enum with its catch-all {@code OTHER}: the
 * HTTP/1 server builds it with {@code HttpMethod.fromNetty}, which keeps an
 * extension method's own name, so {@code TRACK} arrives here as {@code TRACK}.
 * <p>
 * Registered as a Vert.x route filter rather than a JAX-RS filter so it also
 * covers the non-REST routes ({@code /q/*}, the MCP endpoint, static assets),
 * and at a high priority so it runs before authentication and routing.
 */
@ApplicationScoped
public class HttpMethodGuard {

    /** Higher runs first; above the CSP and security-header filters. */
    static final int PRIORITY = 20_000;

    static final Set<String> REFUSED_METHODS = Set.of("TRACE", "TRACK");

    static final String ALLOWED_METHODS = "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS";

    void register(@Observes Filters filters) {
        filters.register(HttpMethodGuard::handle, PRIORITY);
    }

    static void handle(RoutingContext context) {
        String method = context.request().method().name();
        if (method != null && REFUSED_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            context.response()
                    .setStatusCode(405)
                    .putHeader("Allow", ALLOWED_METHODS)
                    .end();
            return;
        }
        context.next();
    }
}
