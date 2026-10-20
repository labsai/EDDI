/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import io.vertx.core.Vertx;
import io.vertx.core.WorkerExecutor;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The scrape route is registered after the security filters and before Quarkus'
 * own handler, on an executor of its own, and only when there is a Prometheus
 * registry and the setting is on.
 */
@DisplayName("MetricsScrapeRoute")
class MetricsScrapeRouteTest {

    @SuppressWarnings("unchecked")
    private static Instance<PrometheusMeterRegistry> registries(boolean resolvable) {
        Instance<PrometheusMeterRegistry> registries = mock(Instance.class);
        when(registries.isResolvable()).thenReturn(resolvable);
        return registries;
    }

    private static Router routerExpecting(Route route) {
        Router router = mock(Router.class);
        when(router.route(anyString())).thenReturn(route);
        when(route.order(anyInt())).thenReturn(route);
        when(route.method(any())).thenReturn(route);
        return router;
    }

    @Test
    @DisplayName("registers GET and HEAD /q/metrics between the security filters and the /q sub-router, on its own executor")
    void registersAfterSecurityBeforeQuarkus() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.createSharedWorkerExecutor(anyString(), anyInt(), anyLong(), any(TimeUnit.class))).thenReturn(mock(WorkerExecutor.class));
        Route route = mock(Route.class);
        Router router = routerExpecting(route);

        new MetricsScrapeRoute(registries(true), vertx, true, "q", "metrics").register(router);

        verify(router).route("/q/metrics");
        var order = ArgumentCaptor.forClass(Integer.class);
        verify(route).order(order.capture());
        int registeredOrder = order.getValue();
        assertTrue(registeredOrder > -100, "must run after the authorization filter (order -100), was " + registeredOrder);
        assertTrue(registeredOrder < 0, "must run before the /q sub-router (order >= 0), was " + registeredOrder);
        verify(route).method(HttpMethod.GET);
        verify(route).method(HttpMethod.HEAD);
        verify(route).handler(any());
        verify(vertx).createSharedWorkerExecutor("eddi-metrics-scrape", MetricsScrapeRoute.SCRAPE_THREADS, 60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("switched off, or without a Prometheus registry: Quarkus keeps the endpoint")
    void staysOutOfTheWay() {
        Vertx vertx = mock(Vertx.class);
        Router router = mock(Router.class);

        new MetricsScrapeRoute(registries(true), vertx, false, "q", "metrics").register(router);
        new MetricsScrapeRoute(registries(false), vertx, true, "q", "metrics").register(router);

        verify(router, never()).route(anyString());
        verify(vertx, never()).createSharedWorkerExecutor(anyString(), anyInt(), anyLong(), any(TimeUnit.class));
    }

    @Test
    @DisplayName("the path follows the two Quarkus settings it is built from")
    void scrapePath() {
        assertEquals("/q/metrics", MetricsScrapeRoute.scrapePath("q", "metrics"));
        assertEquals("/q/metrics", MetricsScrapeRoute.scrapePath("/q/", "metrics"));
        assertEquals("/internal/prom", MetricsScrapeRoute.scrapePath("/internal", "prom"));
        assertEquals("/prom", MetricsScrapeRoute.scrapePath("q", "/prom"));
    }

    @Test
    @DisplayName("content negotiation matches Quarkus' handler")
    void contentType() {
        assertEquals(TextFormat.CONTENT_TYPE_004, MetricsScrapeRoute.contentTypeFor("text/plain"));
        assertEquals(TextFormat.CONTENT_TYPE_004, MetricsScrapeRoute.contentTypeFor("text/html,application/xhtml+xml"));
        assertEquals(TextFormat.CONTENT_TYPE_OPENMETRICS_100, MetricsScrapeRoute.contentTypeFor(null));
        assertEquals(TextFormat.CONTENT_TYPE_OPENMETRICS_100, MetricsScrapeRoute.contentTypeFor("application/openmetrics-text"));
    }
}
