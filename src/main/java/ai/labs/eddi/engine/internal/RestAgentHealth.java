/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IRestAgentHealth;
import ai.labs.eddi.engine.exception.ErrorResponses;
import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthDefaults;
import ai.labs.eddi.engine.health.HealthDrillDownAccess;
import ai.labs.eddi.engine.health.HealthOutcomeViews;
import ai.labs.eddi.engine.health.HealthOutcomes;
import ai.labs.eddi.engine.health.HealthPeriods;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthProfiles;
import ai.labs.eddi.engine.health.HealthProfiles.InvalidProfileException;
import ai.labs.eddi.engine.health.HealthReportLinks;
import ai.labs.eddi.engine.health.HealthReportService;
import ai.labs.eddi.engine.health.HealthReportService.Scope;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.CreatedReportLink;
import ai.labs.eddi.engine.health.report.HealthReports.HealthCompare;
import ai.labs.eddi.engine.health.report.HealthReports.HealthSeries;
import ai.labs.eddi.engine.health.report.HealthReports.OutcomeSuggestions;
import ai.labs.eddi.engine.health.report.HealthReports.ReportLinkInfo;
import ai.labs.eddi.engine.health.report.HealthReports.SessionPage;
import ai.labs.eddi.engine.security.SelfUrlResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestAgentHealth
 */
@ApplicationScoped
public class RestAgentHealth implements IRestAgentHealth {

    private static final Logger LOGGER = Logger.getLogger(RestAgentHealth.class);

    /** The most measures one series call returns. */
    static final int MAX_SERIES_MEASURES = 6;
    static final Set<String> GROUP_BY = Set.of("none", "version", "origin");

    /** The default drill-down page size. */
    static final int DEFAULT_SESSION_PAGE = 50;

    private final HealthAccess access;
    private final HealthReportService reports;
    private final HealthDrillDownAccess drillDown;
    private final HealthOutcomeViews outcomes;

    /** Without the session views — for tests of the other endpoints. */
    public RestAgentHealth(HealthAccess access, HealthReportService reports) {
        this(access, reports, null, null);
    }

    @Inject
    public RestAgentHealth(HealthAccess access, HealthReportService reports, HealthDrillDownAccess drillDown, HealthOutcomeViews outcomes) {
        this.access = access;
        this.reports = reports;
        this.drillDown = drillDown;
        this.outcomes = outcomes;
    }

    /** The health profiles (H4); field-injected so the test constructors stay. */
    @Inject
    HealthProfiles profiles;

    /** The owner report links (H4b); field-injected like the profiles. */
    @Inject
    HealthReportLinks reportLinks;

    /** Where the deployment is reachable, for a report link's URL. */
    @Inject
    SelfUrlResolver selfUrl;

    @Override
    public AgentHealth readHealth(String agentId, String period, String from, String to, Integer version, List<String> origins) {
        String name = access.requireView(agentId);
        Scope base = scope(agentId, period, from, to, version, origins);
        Scope scope = new Scope(base.agentId(), base.window(), base.version(), base.origins(), access.markerTenants(agentId));
        return reports.report(scope, name, access.mayReadRatings(agentId));
    }

    // ------------------------------------------------------------------ profile

    @Override
    public HealthProfile readProfile(String agentId) {
        access.requireView(agentId);
        return profiles.view(agentId, reports.autoPreset(agentId));
    }

    @Override
    public Response updateProfile(String agentId, HealthProfile profile) {
        access.requireEdit(agentId);
        try {
            HealthProfile saved = profiles.save(agentId, profile, access.callerName(), reports.autoPreset(agentId));
            LOGGER.infof("[HEALTH] '%s' saved the health profile of agent %s", sanitize(access.callerName()), sanitize(agentId));
            return Response.ok(saved, MediaType.APPLICATION_JSON_TYPE).build();
        } catch (InvalidProfileException e) {
            return Response.status(Response.Status.BAD_REQUEST).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("error", "bad_request", "message", "The health profile is invalid", "violations", e.violations())).build();
        }
    }

    @Override
    public Response deleteProfile(String agentId) {
        access.requireEdit(agentId);
        if (profiles.delete(agentId)) {
            LOGGER.infof("[HEALTH] '%s' deleted the health profile of agent %s", sanitize(access.callerName()), sanitize(agentId));
        }
        return Response.noContent().build();
    }

    // ------------------------------------------------------------------ owner
    // report links

    @Override
    public Response createReportLink(String agentId, ReportLinkRequest request, UriInfo uriInfo) {
        access.requireEdit(agentId);
        ReportLinkRequest body = request == null ? new ReportLinkRequest(null, null, null) : request;
        List<String> sections = HealthReportLinks.sections(body.sections());
        if (sections.contains(HealthDefaults.QUALITY) && !access.mayReadRatings(agentId)) {
            return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(ErrorResponses.body("forbidden", "sections: quality needs permission to read this agent's ratings")).build();
        }
        CreatedReportLink created = reportLinks.create(agentId, body.expiresInDays(), sections, body.period(), access.callerName(),
                baseUrl(uriInfo));
        return Response.status(Response.Status.CREATED).type(MediaType.APPLICATION_JSON_TYPE)
                .header("Cache-Control", "no-store").entity(created).build();
    }

    /**
     * The public base URL a link is built on: {@code eddi.self.base-url} when set,
     * else the base the request came in on.
     */
    String baseUrl(UriInfo uriInfo) {
        if (selfUrl != null && selfUrl.configured() && selfUrl.baseUrl() != null) {
            return selfUrl.baseUrl();
        }
        if (uriInfo == null || uriInfo.getBaseUri() == null) {
            return "";
        }
        String base = uriInfo.getBaseUri().toString();
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public List<ReportLinkInfo> listReportLinks(String agentId) {
        access.requireEdit(agentId);
        return reportLinks.list(agentId);
    }

    @Override
    public Response revokeReportLink(String agentId, String id) {
        access.requireEdit(agentId);
        if (id == null || id.isBlank() || !reportLinks.revoke(agentId, id.trim(), access.callerName())) {
            return Response.status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(ErrorResponses.body("not_found", "No such report link")).build();
        }
        return Response.noContent().build();
    }

    @Override
    public HealthSeries readSeries(String agentId, List<String> measures, String period, String from, String to, Integer version,
                                   List<String> origins, String groupBy) {
        access.requireView(agentId);
        Scope base = scope(agentId, period, from, to, version, origins);
        Scope scope = new Scope(base.agentId(), base.window(), base.version(), base.origins(), access.markerTenants(agentId));
        List<String> ids = measures(measures);
        String grouping = groupBy == null || groupBy.isBlank() ? "none" : groupBy.trim();
        if (!GROUP_BY.contains(grouping)) {
            throw HealthPeriods.badRequest("groupBy must be none, version or origin — was '" + sanitize(grouping) + "'");
        }
        return reports.series(scope, ids, grouping, access.mayReadRatings(agentId));
    }

    @Override
    public HealthCompare compareVersions(String agentId, Integer fromVersion, Integer toVersion, Integer days, List<String> origins) {
        access.requireView(agentId);
        if (fromVersion == null) {
            throw HealthPeriods.badRequest("from is required: the version to compare from");
        }
        if (toVersion == null) {
            throw HealthPeriods.badRequest("to is required: the version to compare to");
        }
        HealthPeriods.version("from", fromVersion);
        HealthPeriods.version("to", toVersion);
        if (fromVersion.equals(toVersion)) {
            throw HealthPeriods.badRequest("from and to must be different versions");
        }
        int window = days == null ? HealthReportService.DEFAULT_COMPARE_DAYS : days;
        if (window < 1 || window > HealthPeriods.MAX_DAYS) {
            throw HealthPeriods.badRequest("days must be between 1 and " + HealthPeriods.MAX_DAYS + " — was " + window);
        }
        return reports.compare(agentId, fromVersion, toVersion, window, HealthPeriods.origins(origins), access.mayReadRatings(agentId));
    }

    @Override
    public SessionPage listSessions(String agentId, String period, String from, String to, Integer version, List<String> origins,
                                    List<String> outcomeFilter, List<String> evidenceFilter, Integer limit, String cursor) {
        access.requireView(agentId);
        Window window = HealthPeriods.window(period, from, to, "30d", reports.today());
        Integer v = HealthPeriods.version("version", version);
        List<String> requestedOrigins = HealthPeriods.origins(origins);
        List<String> wantedOutcomes = values("outcome", outcomeFilter, HealthOutcomes.ALL);
        List<String> wantedEvidence = values("evidence", evidenceFilter, HealthOutcomes.ALL_EVIDENCE);
        int size = limit == null ? DEFAULT_SESSION_PAGE : limit;
        if (size < 1 || size > HealthOutcomeViews.MAX_PAGE) {
            throw HealthPeriods.badRequest("limit must be between 1 and " + HealthOutcomeViews.MAX_PAGE + " — was " + size);
        }
        int offset = offset(cursor);
        Set<Integer> versions = drillDown.reviewableVersions(agentId, v);
        return outcomes.sessions(agentId, window, versions, reports.effectiveOrigins(agentId, window, requestedOrigins), wantedOutcomes,
                wantedEvidence, size, offset);
    }

    @Override
    public OutcomeSuggestions suggestOutcomes(String agentId, String period, String from, String to, Integer version, List<String> origins) {
        access.requireView(agentId);
        Window window = HealthPeriods.window(period, from, to, "30d", reports.today());
        Integer v = HealthPeriods.version("version", version);
        List<String> effective = reports.effectiveOrigins(agentId, window, HealthPeriods.origins(origins));
        return outcomes.suggestions(agentId, window, v, effective, drillDown.mappedActions(agentId));
    }

    /** Repeated filter values, each checked against {@code allowed}. */
    static List<String> values(String parameter, List<String> requested, Collection<String> allowed) {
        List<String> out = new ArrayList<>();
        if (requested == null) {
            return out;
        }
        for (String value : requested) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String v = value.trim();
            if (!allowed.contains(v)) {
                throw HealthPeriods.badRequest(parameter + " must be one of " + String.join(", ", allowed) + " — was '" + sanitize(v) + "'");
            }
            if (!out.contains(v)) {
                out.add(v);
            }
        }
        return out;
    }

    static int offset(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            int offset = Integer.parseInt(cursor.trim());
            if (offset < 0) {
                throw new NumberFormatException();
            }
            return offset;
        } catch (NumberFormatException e) {
            throw HealthPeriods.badRequest("cursor is not a cursor this endpoint returned");
        }
    }

    private Scope scope(String agentId, String period, String from, String to, Integer version, List<String> origins) {
        return new Scope(agentId, HealthPeriods.window(period, from, to, "30d", reports.today()), HealthPeriods.version("version", version),
                HealthPeriods.origins(origins));
    }

    static List<String> measures(List<String> requested) {
        Set<String> ids = new LinkedHashSet<>();
        if (requested != null) {
            for (String id : requested) {
                if (id == null || id.isBlank()) {
                    continue;
                }
                String measure = id.trim();
                if (!HealthReportService.knownMeasure(measure)) {
                    throw HealthPeriods.badRequest("measure '" + sanitize(measure) + "' is not a known health measure");
                }
                ids.add(measure);
            }
        }
        if (ids.isEmpty()) {
            throw HealthPeriods.badRequest("measure is required (1 to " + MAX_SERIES_MEASURES + " measure ids)");
        }
        if (ids.size() > MAX_SERIES_MEASURES) {
            throw HealthPeriods.badRequest("measure may be given at most " + MAX_SERIES_MEASURES + " times — was " + ids.size());
        }
        return List.copyOf(ids);
    }
}
