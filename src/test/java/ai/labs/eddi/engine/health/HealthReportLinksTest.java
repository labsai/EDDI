/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.health.HealthReportService.Scope;
import ai.labs.eddi.engine.health.model.HealthReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.Check;
import ai.labs.eddi.engine.health.report.HealthReports.CreatedReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.DateRange;
import ai.labs.eddi.engine.health.report.HealthReports.Period;
import ai.labs.eddi.engine.health.report.HealthReports.Reason;
import ai.labs.eddi.engine.health.report.HealthReports.ReportBrand;
import ai.labs.eddi.engine.health.report.HealthReports.ReportLinkInfo;
import ai.labs.eddi.engine.health.report.HealthReports.Section;
import ai.labs.eddi.engine.health.report.HealthReports.SharedHealthReport;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthReportLinks — tokens, hashes, expiry, revocation, sections")
class HealthReportLinksTest {

    static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    private IHealthReportLinkStore store;
    private HealthReportService reports;
    private IDocumentDescriptorStore descriptors;
    private HealthReportLinks links;

    @BeforeEach
    void setUp() {
        store = mock(IHealthReportLinkStore.class);
        reports = mock(HealthReportService.class);
        descriptors = mock(IDocumentDescriptorStore.class);
        when(reports.today()).thenReturn(LocalDate.of(2026, 11, 10));
        links = new HealthReportLinks(store, reports, descriptors, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("a token is 256 random bits, base64url; only its SHA-256 is stored; it is in the response once")
    void tokenAndHash() {
        CreatedReportLink created = links.create("a", null, HealthReportLinks.DEFAULT_SECTIONS, null, "alice", "https://eddi.example");
        ArgumentCaptor<HealthReportLink> stored = ArgumentCaptor.forClass(HealthReportLink.class);
        verify(store).create(stored.capture());
        String token = created.path().substring(HealthReportLinks.UI_PATH.length());
        assertEquals(43, token.length());
        assertEquals(32, Base64.getUrlDecoder().decode(token).length);
        assertEquals("https://eddi.example/manage/health-report/" + token, created.url());
        HealthReportLink link = stored.getValue();
        assertEquals(HealthReportLinks.hash(token), link.tokenHash());
        assertEquals(64, link.tokenHash().length());
        assertNotEquals(token, link.tokenHash());
        assertFalse(link.toString().contains(token), "the token is nowhere in what is stored");
        assertEquals(NOW.plus(Duration.ofDays(30)), link.expiresAt());
        assertEquals("30d", link.period());
        assertEquals("alice", link.createdBy());
        assertTrue(link.id().startsWith("r-"));
        assertEquals(link.id(), created.id());

        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            tokens.add(HealthReportLinks.newToken());
        }
        assertEquals(200, tokens.size());
    }

    @Test
    @DisplayName("expiry 1 to 90 days, a known period, at most 20 live links")
    void limits() {
        assertThrows(BadRequestException.class, () -> links.create("a", 0, HealthReportLinks.DEFAULT_SECTIONS, null, "x", ""));
        assertThrows(BadRequestException.class, () -> links.create("a", 91, HealthReportLinks.DEFAULT_SECTIONS, null, "x", ""));
        assertThrows(BadRequestException.class, () -> links.create("a", 7, HealthReportLinks.DEFAULT_SECTIONS, "1y", "x", ""));
        List<HealthReportLink> full = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            full.add(link("r-" + i, NOW.plusSeconds(60), List.of("reliability")));
        }
        full.add(link("expired", NOW.minusSeconds(1), List.of("reliability")));
        when(store.listByAgent("a")).thenReturn(full);
        assertThrows(BadRequestException.class, () -> links.create("a", 7, HealthReportLinks.DEFAULT_SECTIONS, null, "x", ""));
        verify(store, never()).create(any());
    }

    @Test
    @DisplayName("sections: the defaults leave quality out; unknown ones are refused; display order")
    void sections() {
        assertFalse(HealthReportLinks.sections(null).contains("quality"));
        assertEquals(List.of("reliability", "quality"), HealthReportLinks.sections(List.of("quality", "reliability", "quality")));
        assertThrows(BadRequestException.class, () -> HealthReportLinks.sections(List.of("drilldown")));
    }

    @Test
    @DisplayName("resolve: malformed tokens never reach the store; unknown, expired and revoked are all empty")
    void resolve() {
        assertTrue(links.resolve(null).isEmpty());
        assertTrue(links.resolve("short").isEmpty());
        assertTrue(links.resolve("../../agents/a/health/xxxxxxxxxxxxxxxxxxxxxxxxx").isEmpty());
        verify(store, never()).findByTokenHash(anyString());

        String token = HealthReportLinks.newToken();
        when(store.findByTokenHash(HealthReportLinks.hash(token))).thenReturn(Optional.of(link("r-1", NOW.plusSeconds(1), List.of())));
        assertTrue(links.resolve(token).isPresent());
        String expired = HealthReportLinks.newToken();
        when(store.findByTokenHash(HealthReportLinks.hash(expired))).thenReturn(Optional.of(link("r-2", NOW, List.of())));
        assertTrue(links.resolve(expired).isEmpty(), "expired at this instant");
        assertTrue(links.resolve(HealthReportLinks.newToken()).isEmpty(), "unknown (or revoked: the row is gone)");
    }

    @Test
    @DisplayName("list never shows a token or a hash, and leaves expired links out; revoke deletes by agent and id")
    void listAndRevoke() {
        when(store.listByAgent("a")).thenReturn(List.of(link("r-1", NOW.plusSeconds(60), List.of("reliability")),
                link("r-2", NOW.minusSeconds(60), List.of("reliability"))));
        List<ReportLinkInfo> listed = links.list("a");
        assertEquals(List.of("r-1"), listed.stream().map(ReportLinkInfo::id).toList());
        assertFalse(listed.toString().contains("hash-"), "no token hash in a listing");
        when(store.delete("a", "r-1")).thenReturn(true);
        assertTrue(links.revoke("a", "r-1", "alice"));
        assertFalse(links.revoke("a", "r-9", "alice"));
    }

    @Test
    @DisplayName("render: only the link's sections and their reasons; checks only with reliability; quality read only when shared")
    void render() throws Exception {
        DocumentDescriptor descriptor = new DocumentDescriptor();
        descriptor.setName("Support");
        when(descriptors.readCurrentDescriptor("a")).thenReturn(descriptor);
        Map<String, Object> sections = new LinkedHashMap<>();
        for (String s : HealthDefaults.SECTIONS) {
            sections.put(s, new Section(false, List.of()));
        }
        AgentHealth full = new AgentHealth("a", "Support", new Period("2026-10-12", "2026-11-10", 30), new DateRange("2026-09-12", "2026-10-11"),
                null, List.of("api"), "NEEDS_ATTENTION",
                List.of(new Reason("resolutionRate", "RED", "Resolution 30 %", null), new Reason("apdex", "AMBER", "Apdex 0.80", null),
                        new Reason(null, "INFO", "Not enough data", null)),
                "support", NOW.toString(), "2026-11-10", sections, List.of(new Check("goldenSuite", "Golden", "PASS", null, false, 3)));
        when(reports.report(any(Scope.class), eq("Support"), anyBoolean())).thenReturn(full);
        ReportBrand brand = new ReportBrand("Acme", null, false, "/branding/logo", "#123456");

        SharedHealthReport r = links.render(link("r-1", NOW.plusSeconds(60), List.of("effectiveness", "adoption")), List.of("default"), brand)
                .orElseThrow();
        assertEquals(List.of("effectiveness", "adoption"), List.copyOf(r.sections().keySet()));
        assertEquals(List.of("resolutionRate", "null"), r.reasons().stream().map(x -> String.valueOf(x.measureId())).toList());
        assertTrue(r.checks().isEmpty());
        assertEquals("NEEDS_ATTENTION", r.status());
        assertEquals("Support", r.agentName());
        assertEquals(brand, r.brand());
        verify(reports).report(any(Scope.class), eq("Support"), eq(false));

        links.render(link("r-2", NOW.plusSeconds(60), List.of("reliability", "quality")), List.of("default"), brand);
        verify(reports).report(any(Scope.class), eq("Support"), eq(true));
    }

    @Test
    @DisplayName("render: a deleted agent's link shows nothing")
    void deletedAgent() throws Exception {
        when(descriptors.readCurrentDescriptor("a")).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        assertTrue(links.render(link("r-1", NOW.plusSeconds(60), List.of("reliability")), List.of(), null).isEmpty());
    }

    static HealthReportLink link(String id, Instant expiresAt, List<String> sections) {
        return new HealthReportLink(id, "a", "hash-" + id, sections, "30d", NOW.minusSeconds(3600), "alice", expiresAt);
    }
}
