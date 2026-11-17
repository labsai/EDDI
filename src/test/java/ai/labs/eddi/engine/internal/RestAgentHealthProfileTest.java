/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IRestAgentHealth.ReportLinkRequest;
import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthProfileValidator.Violation;
import ai.labs.eddi.engine.health.HealthProfiles;
import ai.labs.eddi.engine.health.HealthProfiles.InvalidProfileException;
import ai.labs.eddi.engine.health.HealthReportLinks;
import ai.labs.eddi.engine.health.HealthReportService;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.report.HealthReports.CreatedReportLink;
import ai.labs.eddi.engine.security.SelfUrlResolver;
import io.quarkus.security.ForbiddenException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RestAgentHealth — profile and owner report links: permissions and answers")
class RestAgentHealthProfileTest {

    private HealthAccess access;
    private HealthReportService reports;
    private HealthProfiles profiles;
    private HealthReportLinks links;
    private RestAgentHealth rest;

    @BeforeEach
    void setUp() {
        access = mock(HealthAccess.class);
        reports = mock(HealthReportService.class);
        profiles = mock(HealthProfiles.class);
        links = mock(HealthReportLinks.class);
        when(reports.today()).thenReturn(LocalDate.of(2026, 11, 10));
        when(reports.autoPreset("a")).thenReturn("support");
        when(access.requireView("a")).thenReturn("Support");
        when(access.requireEdit("a")).thenReturn("Support");
        when(access.callerName()).thenReturn("alice");
        rest = new RestAgentHealth(access, reports);
        rest.profiles = profiles;
        rest.reportLinks = links;
        rest.selfUrl = new SelfUrlResolver(Optional.empty(), 7070);
    }

    static HealthProfile empty() {
        return new HealthProfile(null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    @DisplayName("GET needs VIEW only; PUT, DELETE and every report-link call need EDIT, checked before anything else")
    void permissions() {
        rest.readProfile("a");
        verify(access).requireView("a");
        verify(access, never()).requireEdit(anyString());

        when(access.requireEdit("v")).thenThrow(new ForbiddenException("no"));
        assertThrows(ForbiddenException.class, () -> rest.updateProfile("v", empty()));
        assertThrows(ForbiddenException.class, () -> rest.deleteProfile("v"));
        assertThrows(ForbiddenException.class, () -> rest.createReportLink("v", null, null));
        assertThrows(ForbiddenException.class, () -> rest.listReportLinks("v"));
        assertThrows(ForbiddenException.class, () -> rest.revokeReportLink("v", "r-1"));
        verify(profiles, never()).save(eq("v"), any(), anyString(), anyString());
        verify(profiles, never()).delete("v");
        verify(links, never()).list("v");
    }

    @Test
    @DisplayName("PUT answers 200 with the effective profile, or 400 with every violation")
    void put() {
        HealthProfile saved = empty();
        when(profiles.save("a", saved, "alice", "support")).thenReturn(saved);
        Response ok = rest.updateProfile("a", saved);
        assertEquals(200, ok.getStatus());
        assertSame(saved, ok.getEntity());

        HealthProfile bad = empty();
        when(profiles.save("a", bad, "alice", "support"))
                .thenThrow(new InvalidProfileException(List.of(new Violation("thresholds.resolutionRate.red", "must be below green"))));
        Response invalid = rest.updateProfile("a", bad);
        assertEquals(400, invalid.getStatus());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) invalid.getEntity();
        assertEquals(List.of(new Violation("thresholds.resolutionRate.red", "must be below green")), body.get("violations"));
    }

    @Test
    @DisplayName("DELETE answers 204 whether or not a profile was stored")
    void delete() {
        assertEquals(204, rest.deleteProfile("a").getStatus());
        verify(profiles).delete("a");
    }

    @Test
    @DisplayName("quality in a link needs the ratings rule: 403, and no link is created")
    void qualityNeedsRatings() {
        when(access.mayReadRatings("a")).thenReturn(false);
        Response r = rest.createReportLink("a", new ReportLinkRequest(7, List.of("reliability", "quality"), null), null);
        assertEquals(403, r.getStatus());
        verify(links, never()).create(anyString(), any(), any(), any(), anyString(), any());

        when(access.mayReadRatings("a")).thenReturn(true);
        when(links.create(eq("a"), eq(7), eq(List.of("reliability", "quality")), any(), eq("alice"), any()))
                .thenReturn(new CreatedReportLink("r-1", "u", "/manage/health-report/t", "x", List.of(), "30d"));
        assertEquals(201, rest.createReportLink("a", new ReportLinkRequest(7, List.of("reliability", "quality"), null), null).getStatus());
    }

    @Test
    @DisplayName("the link's URL is built on eddi.self.base-url when set, else on the request's base")
    void baseUrl() {
        UriInfo uri = mock(UriInfo.class);
        when(uri.getBaseUri()).thenReturn(URI.create("https://eddi.example/"));
        assertEquals("https://eddi.example", rest.baseUrl(uri));
        rest.selfUrl = new SelfUrlResolver(Optional.of("https://public.example"), 7070);
        assertEquals("https://public.example", rest.baseUrl(uri));
    }

    @Test
    @DisplayName("revoking an unknown link is 404, a known one 204")
    void revoke() {
        when(links.revoke("a", "r-1", "alice")).thenReturn(true);
        assertEquals(204, rest.revokeReportLink("a", "r-1").getStatus());
        assertEquals(404, rest.revokeReportLink("a", "r-2").getStatus());
        assertEquals(404, rest.revokeReportLink("a", " ").getStatus());
    }
}
