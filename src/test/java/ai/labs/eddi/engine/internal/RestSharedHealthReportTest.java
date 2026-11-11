/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.branding.BrandingService;
import ai.labs.eddi.configs.branding.model.ResolvedBrand;
import ai.labs.eddi.engine.api.IRestAgentHealth;
import ai.labs.eddi.engine.api.IRestHealthPortfolio;
import ai.labs.eddi.engine.api.IRestSharedHealthReport;
import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthReportLinks;
import ai.labs.eddi.engine.health.HealthReportRateLimiter;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.SharedHealthReport;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RestSharedHealthReport — the unauthenticated owner report")
class RestSharedHealthReportTest {

    private HealthReportLinks links;
    private HealthReportRateLimiter limiter;
    private RestSharedHealthReport rest;
    private final HealthReportLink link = new HealthReportLink("r-1", "a", "hash", List.of("reliability"), "30d", Instant.EPOCH, "alice",
            Instant.parse("2099-01-01T00:00:00Z"));

    @BeforeEach
    void setUp() {
        links = mock(HealthReportLinks.class);
        limiter = mock(HealthReportRateLimiter.class);
        when(limiter.tryRequest()).thenReturn(true);
        when(limiter.tryMiss()).thenReturn(true);
        when(limiter.tryLink(anyString())).thenReturn(true);
        BrandingService branding = mock(BrandingService.class);
        when(branding.current(any())).thenReturn(ResolvedBrand.defaults(false));
        rest = new RestSharedHealthReport(links, limiter, mock(HealthAccess.class), branding);
    }

    static void assertNoStore(Response r) {
        assertEquals("no-store, max-age=0", r.getHeaderString("Cache-Control"));
        assertEquals("no-referrer", r.getHeaderString("Referrer-Policy"));
        assertNotNull(r.getHeaderString("X-Robots-Tag"));
        assertNull(r.getHeaderString("Access-Control-Allow-Origin"), "no CORS of its own");
    }

    @Test
    @DisplayName("an unknown, expired or revoked token is a plain 404 — the same body for all")
    void notFound() {
        when(links.resolve(anyString())).thenReturn(Optional.empty());
        Response r = rest.readSharedReport("whatever", "h");
        assertEquals(404, r.getStatus());
        assertEquals(Map.of("error", "not_found", "message", "No such report"), r.getEntity());
        assertNoStore(r);
        verify(links, never()).render(any(), any(), any());
    }

    @Test
    @DisplayName("a live link answers 200 with the report, never cached")
    void ok() {
        SharedHealthReport report = new SharedHealthReport("Support", null, null, "HEALTHY", List.of(), "support", null, null, Map.of(), List.of(),
                link.expiresAt().toString(), null);
        when(links.resolve("t")).thenReturn(Optional.of(link));
        when(links.render(any(), any(), any())).thenReturn(Optional.of(report));
        Response r = rest.readSharedReport("t", "h");
        assertEquals(200, r.getStatus());
        assertEquals(report, r.getEntity());
        assertNoStore(r);
    }

    @Test
    @DisplayName("rate limits: all reports, unknown tokens, one link — each a 429 with Retry-After")
    void limits() {
        when(limiter.tryRequest()).thenReturn(false);
        Response global = rest.readSharedReport("t", "h");
        assertEquals(429, global.getStatus());
        assertEquals("60", global.getHeaderString("Retry-After"));
        verify(links, never()).resolve(anyString());

        when(limiter.tryRequest()).thenReturn(true);
        when(links.resolve("unknown")).thenReturn(Optional.empty());
        when(limiter.tryMiss()).thenReturn(false);
        assertEquals(429, rest.readSharedReport("unknown", "h").getStatus());

        when(links.resolve("t")).thenReturn(Optional.of(link));
        when(limiter.tryLink("hash")).thenReturn(false);
        assertEquals(429, rest.readSharedReport("t", "h").getStatus());
        verify(links, never()).render(any(), any(), any());
    }

    @Test
    @DisplayName("a link whose agent is gone is 404; a failing render is 500 without detail")
    void renderEdges() {
        when(links.resolve("t")).thenReturn(Optional.of(link));
        when(links.render(any(), any(), any())).thenReturn(Optional.empty());
        assertEquals(404, rest.readSharedReport("t", "h").getStatus());
        when(links.render(any(), any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));
        Response r = rest.readSharedReport("t", "h");
        assertEquals(500, r.getStatus());
        assertTrue(!String.valueOf(r.getEntity()).contains("hunter2"));
    }

    @Test
    @DisplayName("only this interface is @PermitAll; the owner-side endpoints keep their roles")
    void annotations() throws Exception {
        assertNotNull(IRestSharedHealthReport.class.getAnnotation(PermitAll.class));
        assertNotNull(IRestAgentHealth.class.getAnnotation(RolesAllowed.class));
        assertNotNull(IRestHealthPortfolio.class.getAnnotation(RolesAllowed.class));
        assertNull(IRestAgentHealth.class.getAnnotation(PermitAll.class));
    }

    @Test
    @DisplayName("application.properties permits exactly GET/HEAD of /health/reports/* without a token")
    void permitRule() throws Exception {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(Path.of("src", "main", "resources", "application.properties"))) {
            p.load(in);
        }
        assertEquals("/health/reports/*", p.getProperty("quarkus.http.auth.permission.health-report.paths"));
        assertEquals("permit", p.getProperty("quarkus.http.auth.permission.health-report.policy"));
        assertEquals("GET,HEAD", p.getProperty("quarkus.http.auth.permission.health-report.methods"));
        p.stringPropertyNames().stream().filter(k -> k.endsWith(".paths") && k.startsWith("quarkus.http.auth.permission."))
                .filter(k -> !k.contains(".health-report.")).forEach(k -> {
                    for (String path : p.getProperty(k).split(",")) {
                        assertTrue(!path.trim().startsWith("/health") && !path.trim().startsWith("/agents"), k + " opens " + path);
                    }
                });
    }
}
