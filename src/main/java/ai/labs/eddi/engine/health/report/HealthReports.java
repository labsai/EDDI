/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.report;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * The wire shapes of the Agent Health REST API — exactly those of
 * {@code planning/agent-health-api.md}. Every record writes its nulls
 * ({@code "status": null}, {@code "value": null}): the contract distinguishes a
 * field that is null from one that is absent, and EDDI's mapper drops nulls by
 * default. Days are {@code YYYY-MM-DD} and instants ISO-8601 strings, both UTC.
 */
public final class HealthReports {

    private HealthReports() {
    }

    /** A Wilson or normal-approximation interval. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Interval(double low, double high) {
    }

    /** The band limits of a scored measure. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Thresholds(double green, double red, boolean higherIsBetter) {
    }

    /** One part of a measure — a failure class, an origin, a resolution source. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record BreakdownEntry(String key, String label, Double value, long n) {
    }

    /**
     * One measure of a section.
     *
     * @param tested
     *            whether this measure's kind has a significance test at all (rates,
     *            Apdex, counts) — {@code false} for one that is never tested (a
     *            percentile, a cost or token average, a ratio without variance), so
     *            its {@code significant: false} says nothing about the change. A
     *            property of the measure, not of the period's data
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Measure(String id, String label, String kind, boolean critical, String unit, Double value, long n, Interval interval,
            Double baseline, Double delta, boolean significant, boolean tested, String band, Thresholds thresholds, List<BreakdownEntry> breakdown,
            String hint) {

        /** Without {@code tested} (false) — for tests and hand-built measures. */
        public Measure(String id, String label, String kind, boolean critical, String unit, Double value, long n, Interval interval, Double baseline,
                Double delta, boolean significant, String band, Thresholds thresholds, List<BreakdownEntry> breakdown, String hint) {
            this(id, label, kind, critical, unit, value, n, interval, baseline, delta, significant, false, band, thresholds, breakdown, hint);
        }
    }

    /** A section the caller may read, or {@code restricted} with no measures. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Section(boolean restricted, List<Measure> measures) {
    }

    /** The effectiveness section, which also states its outcome coverage. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record EffectivenessSection(boolean restricted, Double coverage, List<Measure> measures) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record DateRange(String from, String to) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Period(String from, String to, int days) {
    }

    /** A dated check — the golden suite on the deployed version. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Check(String id, String label, String state, String at, boolean stale, Integer version) {
    }

    /**
     * Why an agent has its status (H4).
     *
     * @param severity
     *            {@code RED} (a red band), {@code AMBER} (an amber band or a
     *            significant change for the worse) or {@code INFO} (worth knowing,
     *            no effect on the status — "mostly assumed from silence", "not
     *            enough data")
     * @param text
     *            English, short, with the value and its change; shown as is
     * @param markerId
     *            the change marker that likely explains it, or {@code null}
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Reason(String measureId, String severity, String text, String markerId) {
    }

    /** {@code GET /agents/{agentId}/health}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AgentHealth(String agentId, String agentName, Period period, DateRange baseline, Integer version, List<String> origins,
            String status, List<Reason> reasons, String preset, String generatedAt, String dataThrough, Map<String, Object> sections,
            List<Check> checks) {
    }

    /**
     * The brand an owner report is shown under (white-labelling): what the shells
     * already show publicly, nothing more.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReportBrand(String productName, String tagline, boolean poweredBy, String logoUrl, String primaryColor) {
    }

    /**
     * {@code GET /health/reports/{token}} — the §1 shape limited to the link's
     * sections, without drill-down, plus the brand and the link's expiry.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SharedHealthReport(String agentName, Period period, DateRange baseline, String status, List<Reason> reasons, String preset,
            String generatedAt, String dataThrough, Map<String, Object> sections, List<Check> checks, String expiresAt, ReportBrand brand) {
    }

    /** One owner report link as listed — never its token. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReportLinkInfo(String id, String expiresAt, List<String> sections, String period, String createdAt, String createdBy) {
    }

    /** A link just created: the only time its token (in {@code url}) is shown. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CreatedReportLink(String id, String url, String path, String expiresAt, List<String> sections, String period) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SeriesPoint(String day, Double value, long n) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Series(String measure, String group, String unit, List<SeriesPoint> points) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Marker(String id, String at, String kind, String label, Integer version) {
    }

    /** {@code GET /agents/{agentId}/health/series}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HealthSeries(String agentId, Period period, List<Series> series, List<Marker> markers) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CompareSide(int version, DateRange window, Map<String, Object> sections) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CompareDelta(String measureId, Double from, Double to, Double delta, boolean significant, Boolean better) {
    }

    /** {@code GET /agents/{agentId}/health/compare}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record HealthCompare(String agentId, CompareSide from, CompareSide to, List<CompareDelta> deltas) {
    }

    /**
     * One portfolio row. {@code sessions} is {@code null} when the period reaches
     * past the turn-row retention (then {@code hint} says so), and
     * {@code sessionsDelta} when the baseline does.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PortfolioItem(String agentId, String agentName, String status, String preset, Reason topReason, Long sessions,
            Double sessionsDelta, List<Measure> keyMeasures, boolean platformIncident, String hint) {
    }

    /** Days on which platform failures of one class hit several agents at once. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record PlatformIncident(String from, String to, String failureClass, int agents) {
    }

    /** {@code GET /health/agents}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Portfolio(List<PortfolioItem> items, List<PlatformIncident> platformIncidents, String nextCursor) {
    }

    /**
     * One session of the drill-down — ids, counts and the outcome, no user id and
     * no content.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SessionRow(String conversationId, int sessionNo, String startedAt, String endedAt, Integer agentVersion, String origin,
            boolean engaged, String outcome, String evidence, boolean provisional, int turns, double costUsd, int failures) {
    }

    /** {@code GET /agents/{agentId}/health/sessions}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record SessionPage(List<SessionRow> items, String nextCursor) {
    }

    /** One action that often ends sessions and is not mapped to an outcome yet. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutcomeSuggestion(String action, String suggested, long sessionsEndingWith, String reason) {
    }

    /** {@code GET /agents/{agentId}/health/outcome-suggestions}. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record OutcomeSuggestions(List<OutcomeSuggestion> items) {
    }
}
