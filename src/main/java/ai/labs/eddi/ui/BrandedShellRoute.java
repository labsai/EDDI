/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.ui;

import ai.labs.eddi.configs.branding.BrandRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;

/**
 * Serves the HTML files Quarkus would otherwise hand out verbatim from
 * {@code META-INF/resources} — the root redirect at {@code /} and
 * {@code /index.html}, and each shell under its own file name
 * ({@code /manage.html}, …) — rendered with the brand.
 * <p>
 * The shells' own paths ({@code /manage}, {@code /chat}, …) are JAX-RS
 * resources and render through {@link BrandedShellRenderer} already; these are
 * the static paths, which a JAX-RS resource cannot shadow. The route runs after
 * the security and CSP filters and before the static handler. A shell missing
 * from the classpath falls through to that handler unchanged.
 */
@ApplicationScoped
public class BrandedShellRoute {

    /** After authorization (-100) and the CSP filters (-30 to -20). */
    static final int ORDER = -10;

    static final Map<String, BrandedShell> PATHS = Map.of(
            "/", BrandedShell.ROOT,
            "/index.html", BrandedShell.ROOT,
            "/manage.html", BrandedShell.MANAGE,
            "/welcome.html", BrandedShell.WELCOME,
            "/workforce.html", BrandedShell.WORKFORCE,
            "/chat.html", BrandedShell.CHAT);

    private final BrandedShellRenderer renderer;

    @Inject
    public BrandedShellRoute(BrandedShellRenderer renderer) {
        this.renderer = renderer;
    }

    void register(@Observes Router router) {
        for (var entry : PATHS.entrySet()) {
            BrandedShell shell = entry.getValue();
            router.route(entry.getKey()).order(ORDER).method(HttpMethod.GET).method(HttpMethod.HEAD)
                    .blockingHandler(context -> handle(context, shell));
        }
    }

    void handle(RoutingContext context, BrandedShell shell) {
        Optional<String> page = renderer.render(shell, BrandRequest.ofHost(context.request().getHeader("Host")));
        if (page.isEmpty()) {
            context.next();
            return;
        }
        context.response()
                .putHeader("Content-Type", "text/html;charset=UTF-8")
                .putHeader("Cache-Control", "no-cache, must-revalidate")
                .end(page.get());
    }
}
