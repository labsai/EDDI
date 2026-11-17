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
import ai.labs.eddi.engine.health.HealthReportRateLimiter.Charge;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.SharedHealthReport;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard.UseCheck;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.SocketAddress;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("RestSharedHealthReport — the unauthenticated owner report")
class RestSharedHealthReportTest {

    private HealthReportLinks links;
    private HealthReportRateLimiter limiter;
    private RestSharedHealthReport rest;
    private final HealthReportLink link = new HealthReportLink("r-1", "a", "hash", List.of("reliability"), "30d", Instant.EPOCH, "alice",
            Instant.parse("2099-01-01T00:00:00Z"));

    static final String TOKEN = "A".repeat(43);
    static final String HASH = HealthReportLinks.hash(TOKEN);

    @BeforeEach
    void setUp() {
        links = mock(HealthReportLinks.class);
        limiter = mock(HealthReportRateLimiter.class);
        when(limiter.tryLookup(anyString(), anyString())).thenReturn(new Charge(true, "client", true));
        when(limiter.tryRead(anyString())).thenReturn(true);
        when(links.authorized(any())).thenReturn(UseCheck.ALLOWED);
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
    @DisplayName("a malformed token is a 404 before any rate limit or lookup")
    void malformed() {
        for (String token : new String[]{"whatever", "../agents", "A".repeat(42), "A".repeat(44), "A".repeat(42) + "="}) {
            Response r = rest.readSharedReport(token, "h", null);
            assertEquals(404, r.getStatus(), token);
            assertNoStore(r);
        }
        verifyNoInteractions(limiter);
        verify(links, never()).resolveHash(anyString());
    }

    @Test
    @DisplayName("an unknown, expired or revoked token is a plain 404 — the same body for all — and is forgotten")
    void notFound() {
        when(links.resolveHash(HASH)).thenReturn(Optional.empty());
        Response r = rest.readSharedReport(TOKEN, "h", null);
        assertEquals(404, r.getStatus());
        assertEquals(Map.of("error", "not_found", "message", "No such report"), r.getEntity());
        assertNoStore(r);
        verify(limiter).missed(HASH);
        verify(limiter, never()).tryRead(anyString());
        verify(links, never()).render(any(), any(), any());
    }

    @Test
    @DisplayName("a live link answers 200 with the report, never cached; the lookup charge is given back")
    void ok() {
        SharedHealthReport report = new SharedHealthReport("Support", null, null, "HEALTHY", List.of(), "support", null, null, Map.of(), List.of(),
                link.expiresAt().toString(), null);
        when(links.resolveHash(HASH)).thenReturn(Optional.of(link));
        when(links.render(any(), any(), any())).thenReturn(Optional.of(report));
        Response r = rest.readSharedReport(TOKEN, "h", null);
        assertEquals(200, r.getStatus());
        assertEquals(report, r.getEntity());
        assertNoStore(r);
        verify(limiter).found(eq(HASH), any());
        verify(limiter).tryRead(HASH);
    }

    @Test
    @DisplayName("rate limits: a refused lookup never reaches the store; a found link over its reads is 429 with Retry-After")
    void limits() {
        when(limiter.tryLookup(anyString(), anyString())).thenReturn(new Charge(false, "client", false));
        Response refused = rest.readSharedReport(TOKEN, "h", null);
        assertEquals(429, refused.getStatus());
        assertEquals("60", refused.getHeaderString("Retry-After"));
        verify(links, never()).resolveHash(anyString());

        when(limiter.tryLookup(anyString(), anyString())).thenReturn(new Charge(true, "client", true));
        when(links.resolveHash(HASH)).thenReturn(Optional.of(link));
        when(limiter.tryRead(HASH)).thenReturn(false);
        assertEquals(429, rest.readSharedReport(TOKEN, "h", null).getStatus());
        verify(links, never()).render(any(), any(), any());
    }

    @Test
    @DisplayName("unknown and malformed tokens cannot starve a valid link (with the real limiter)")
    void scanCannotStarve() {
        HealthReportRateLimiter real = new HealthReportRateLimiter();
        rest = new RestSharedHealthReport(links, real, mock(HealthAccess.class), mock(BrandingService.class));
        when(links.resolveHash(anyString())).thenReturn(Optional.empty());
        when(links.resolveHash(HASH)).thenReturn(Optional.of(link));
        when(links.render(any(), any(), any())).thenReturn(Optional.of(new SharedHealthReport("S", null, null, "HEALTHY", List.of(), null, null,
                null, Map.of(), List.of(), null, null)));
        assertEquals(200, rest.readSharedReport(TOKEN, "h", null).getStatus(), "the owner opens the link");
        int refused = 0;
        for (int i = 0; i < 2_000; i++) {
            String junk = String.format("%043d", i).replace('0', 'B');
            int status = rest.readSharedReport(junk, "h", null).getStatus();
            refused += status == 429 ? 1 : 0;
            rest.readSharedReport("x" + i, "h", null);
        }
        assertTrue(refused > 1_000, "the scan is throttled");
        for (int i = 0; i < 20; i++) {
            assertEquals(200, rest.readSharedReport(TOKEN, "h", null).getStatus(), "read " + i + " of the real link");
        }
    }

    @Test
    @DisplayName("strict rights: a creator who lost EDIT makes the link a 404; an unanswerable check is a 503, never the report")
    void creatorRights() {
        when(links.resolveHash(HASH)).thenReturn(Optional.of(link));
        when(links.authorized(link)).thenReturn(UseCheck.DENIED);
        assertEquals(404, rest.readSharedReport(TOKEN, "h", null).getStatus());
        when(links.authorized(link)).thenReturn(UseCheck.UNKNOWN);
        assertEquals(503, rest.readSharedReport(TOKEN, "h", null).getStatus());
        verify(links, never()).render(any(), any(), any());
    }

    @Test
    @DisplayName("a link whose agent is gone is 404; a failing render is 500 without detail")
    void renderEdges() {
        when(links.resolveHash(HASH)).thenReturn(Optional.of(link));
        when(links.render(any(), any(), any())).thenReturn(Optional.empty());
        assertEquals(404, rest.readSharedReport(TOKEN, "h", null).getStatus());
        when(links.render(any(), any(), any())).thenThrow(new IllegalStateException("db password is hunter2"));
        Response r = rest.readSharedReport(TOKEN, "h", null);
        assertEquals(500, r.getStatus());
        assertTrue(!String.valueOf(r.getEntity()).contains("hunter2"));
    }

    @Test
    @DisplayName("with a Vert.x request, Referrer-Policy replaces the deployment-wide value on the response instead of adding a second")
    void referrerPolicyReplaces() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        HttpServerResponse response = mock(HttpServerResponse.class);
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().add("Referrer-Policy", "strict-origin-when-cross-origin");
        when(request.response()).thenReturn(response);
        when(response.headers()).thenReturn(headers);
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(1234, "192.0.2.7"));
        when(links.resolveHash(HASH)).thenReturn(Optional.empty());

        Response r = rest.readSharedReport(TOKEN, "h", request);

        assertEquals(List.of("no-referrer"), headers.getAll("Referrer-Policy"));
        assertNull(r.getHeaderString("Referrer-Policy"), "not on the JAX-RS response as well");
        verify(limiter).tryLookup(HASH, "192.0.2.7");
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
