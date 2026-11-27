/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IRestAgentHealth;
import ai.labs.eddi.engine.health.HealthAccess;
import ai.labs.eddi.engine.health.HealthDrillDownAccess;
import ai.labs.eddi.engine.health.HealthOutcomeViews;
import ai.labs.eddi.engine.health.HealthOutcomes;
import ai.labs.eddi.engine.health.HealthPeriods;
import ai.labs.eddi.engine.health.HealthReportService;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.HealthReportService.Scope;
import ai.labs.eddi.engine.health.report.HealthReports.AgentHealth;
import ai.labs.eddi.engine.health.report.HealthReports.HealthCompare;
import ai.labs.eddi.engine.health.report.HealthReports.HealthSeries;
import ai.labs.eddi.engine.health.report.HealthReports.OutcomeSuggestions;
import ai.labs.eddi.engine.health.report.HealthReports.SessionPage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * @see IRestAgentHealth
 */
@ApplicationScoped
public class RestAgentHealth implements IRestAgentHealth {

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

    @Override
    public AgentHealth readHealth(String agentId, String period, String from, String to, Integer version, List<String> origins) {
        String name = access.requireView(agentId);
        Scope scope = scope(agentId, period, from, to, version, origins);
        return reports.report(scope, name, access.mayReadRatings(agentId));
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
