/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import io.quarkus.arc.Arc;
import io.quarkus.arc.ManagedContext;
import io.quarkus.runtime.ShutdownEvent;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.concurrent.TimeUnit;

/**
 * Serves the Prometheus scrape ({@code GET /q/metrics}) on threads of its own,
 * so monitoring keeps working while the node is overloaded.
 * <p>
 * Quarkus registers the endpoint as a blocking route, which runs on the same
 * worker pool as the blocking REST endpoints. In a 5,000-user burst against a
 * node held to two CPUs, conversation starts held all 200 of that pool's
 * threads in database waits, and the scrape queued behind them: {@code
 * /q/metrics} did not answer within 5 s for 106 s, while turns were still
 * completing. The dashboards went blind exactly when they were needed.
 * <p>
 * This route runs ahead of Quarkus' handler (which it then never reaches) and
 * produces the same response on a dedicated two-thread executor. It sits after
 * the security filters, so the {@code /q/metrics} permission
 * ({@code eddi.metrics.http-policy}) is enforced exactly as before.
 * {@code eddi.metrics.dedicated-scrape-threads=false} hands the endpoint back
 * to Quarkus.
 */
@ApplicationScoped
public class MetricsScrapeRoute {
    private static final Logger LOGGER = Logger.getLogger(MetricsScrapeRoute.class);

    /**
     * After authentication (-200) and authorization (-100) and the CSP filters (-30
     * to -20), before the {@code /q} sub-router, which is mounted at an order of
     * zero or above.
     */
    static final int ORDER = -10;

    static final int SCRAPE_THREADS = 2;

    private final Instance<PrometheusMeterRegistry> registries;
    private final Vertx vertx;
    private final boolean enabled;
    private final String path;
    private volatile WorkerExecutor executor;

    @Inject
    public MetricsScrapeRoute(Instance<PrometheusMeterRegistry> registries, Vertx vertx,
            @ConfigProperty(name = "eddi.metrics.dedicated-scrape-threads", defaultValue = "true") boolean enabled,
            @ConfigProperty(name = "quarkus.http.non-application-root-path", defaultValue = "q") String nonApplicationRoot,
            @ConfigProperty(name = "quarkus.micrometer.export.prometheus.path", defaultValue = "metrics") String metricsPath) {
        this.registries = registries;
        this.vertx = vertx;
        this.enabled = enabled;
        this.path = scrapePath(nonApplicationRoot, metricsPath);
    }

    /**
     * {@code /q/metrics} from its two settings, either of which may be absolute.
     */
    static String scrapePath(String nonApplicationRoot, String metricsPath) {
        if (metricsPath.startsWith("/")) {
            return metricsPath;
        }
        String root = nonApplicationRoot.startsWith("/") ? nonApplicationRoot : "/" + nonApplicationRoot;
        return (root.endsWith("/") ? root : root + "/") + metricsPath;
    }

    void register(@Observes Router router) {
        if (!enabled || !registries.isResolvable()) {
            return;
        }
        executor = vertx.createSharedWorkerExecutor("eddi-metrics-scrape", SCRAPE_THREADS, 60, TimeUnit.SECONDS);
        router.route(path).order(ORDER).method(HttpMethod.GET).method(HttpMethod.HEAD).handler(this::handle);
        LOGGER.debugf("Serving %s on %d dedicated threads", path, SCRAPE_THREADS);
    }

    void handle(RoutingContext routingContext) {
        String contentType = contentTypeFor(routingContext.request().getHeader("Accept"));
        PrometheusMeterRegistry registry = registries.get();
        executor.<String>executeBlocking(() -> scrape(registry, contentType), false).onComplete(result -> {
            if (result.succeeded()) {
                routingContext.response().putHeader("Content-Type", contentType).end(Buffer.buffer(result.result()));
            } else {
                routingContext.fail(result.cause());
            }
        });
    }

    /**
     * Same negotiation as Quarkus' handler: the 0.0.4 text format when asked for,
     * OpenMetrics otherwise.
     */
    static String contentTypeFor(String acceptHeader) {
        if (acceptHeader != null && (acceptHeader.contains("text/plain") || acceptHeader.contains("text/html"))) {
            return TextFormat.CONTENT_TYPE_004;
        }
        return TextFormat.CONTENT_TYPE_OPENMETRICS_100;
    }

    private static String scrape(PrometheusMeterRegistry registry, String contentType) {
        // As Quarkus' handler does: a gauge may read a request-scoped bean.
        ManagedContext requestContext = Arc.container().requestContext();
        if (requestContext.isActive()) {
            return registry.scrape(contentType);
        }
        requestContext.activate();
        try {
            return registry.scrape(contentType);
        } finally {
            requestContext.terminate();
        }
    }

    void close(@Observes ShutdownEvent event) {
        WorkerExecutor current = executor;
        if (current != null) {
            current.close();
        }
    }
}
