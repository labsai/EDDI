/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Sends {@code Strict-Transport-Security} when the operator says EDDI is only
 * ever reached over https.
 * <p>
 * Off by default ({@code eddi.security.hsts.enabled=false}): EDDI usually
 * serves plain http behind a TLS-terminating proxy, and the quick start serves
 * plain http on localhost, where the header is ignored by browsers anyway but
 * would make no promise worth sending. A deployment whose public origin is
 * https — the Helm chart with {@code ingress.tls} set does this — turns it on,
 * so a browser that has seen the origin once refuses a downgraded plain-http
 * connection afterwards. The header is sent whatever the request's own scheme,
 * because behind the proxy every request EDDI sees is http.
 * <p>
 * {@code includeSubDomains} and {@code preload} are deliberately not offered:
 * they bind every sibling host of the domain, which is not EDDI's to decide.
 * Set them at the proxy if the whole domain is https-only.
 */
@ApplicationScoped
public class HstsHeaderFilter {

    static final String HEADER = "Strict-Transport-Security";

    /** Below the request guards, which may refuse a request outright. */
    static final int PRIORITY = 18_000;

    @ConfigProperty(name = "eddi.security.hsts.enabled", defaultValue = "false")
    boolean enabled;

    @ConfigProperty(name = "eddi.security.hsts.max-age", defaultValue = "31536000")
    long maxAgeSeconds = 31_536_000L;

    void register(@Observes Filters filters) {
        if (!enabled) {
            return;
        }
        String value = headerValue(maxAgeSeconds);
        filters.register(context -> handle(context, value), PRIORITY);
    }

    static String headerValue(long maxAgeSeconds) {
        return "max-age=" + Math.max(0L, maxAgeSeconds);
    }

    static void handle(RoutingContext context, String value) {
        context.response().putHeader(HEADER, value);
        context.next();
    }
}
