/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static ai.labs.eddi.docs.MonitoringSeries.collectMeters;
import static ai.labs.eddi.docs.MonitoringSeries.knownSeries;
import static ai.labs.eddi.docs.MonitoringSeries.metricNames;
import static ai.labs.eddi.docs.MonitoringSeries.read;
import static ai.labs.eddi.docs.MonitoringSeries.repoRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the Grafana dashboards honest: about covering every meter, and about
 * querying series that exist.
 * <p>
 * {@code docs/metrics.md} promises that the Full Metrics Reference dashboard
 * "covers all registered meters". That was a description of intent, not of a
 * mechanism: nothing generated the file and nothing checked it. By the time the
 * claim was audited it was already false — the five {@code eddi.llm.cascade.*}
 * decision counters were registered and on no panel.
 * <p>
 * The first version of this test then only checked the Full dashboard, and by
 * substring. A live review evaluated all 360 panel queries against Prometheus
 * and found what that let through: a query for
 * {@code eddi_nats_dead_letter_count} (the meter is scraped as
 * {@code ..._total}), a {@code $job} variable sourced from {@code jvm_info}
 * (scraped as {@code jvm_info_total}), HTTP percentile panels over a histogram
 * nobody publishes, duplicate panel ids, and an operations dashboard with its
 * job and datasource uid hard-coded. Every dashboard is now parsed as JSON and
 * every query checked against the series a scrape can actually contain.
 *
 * @see MonitoringSeries
 * @see AlertRulesTest
 */
@DisplayName("metrics dashboard coverage")
class MetricsDashboardCoverageTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Path MONITORING = Path.of("docs", "monitoring");

    private static final Path FULL_DASHBOARD = MONITORING.resolve("eddi-full-metrics-dashboard.json");

    private static final Path METRICS_REFERENCE = Path.of("docs", "metrics.md");

    /**
     * Meters that are registered but deliberately not on the Full dashboard, as
     * {@code meter → why}.
     * <p>
     * Empty, and it should stay that way. An entry here is a claim that a number
     * the code bothers to record is not worth looking at, which is an argument to
     * make in review rather than a default to fall into.
     */
    private static final Set<String> INTENTIONALLY_UNCHARTED = Set.of();

    @Test
    @DisplayName("every registered eddi meter appears on the Full Metrics Reference dashboard")
    void everyRegisteredMeterIsCharted() {
        Path root = repoRoot();
        // Metric names parsed out of the queries, not a substring search of the
        // file: eddi_tool_cache_hits_total is a substring of
        // eddi_tool_cache_hits_by_tool_total, and a meter named only in a panel
        // description is not charted.
        var queried = new TreeSet<String>();
        for (String expr : expressions(dashboard(root.resolve(FULL_DASHBOARD)))) {
            queried.addAll(metricNames(expr));
        }

        var uncharted = new TreeSet<String>();
        for (var meter : collectMeters(root).values()) {
            if (INTENTIONALLY_UNCHARTED.contains(meter.name())) {
                continue;
            }
            if (meter.series().stream().noneMatch(queried::contains)) {
                uncharted.add(meter.describe());
            }
        }

        assertTrue(uncharted.isEmpty(),
                "docs/metrics.md promises the Full Metrics Reference covers every registered meter. "
                        + "These are registered and queried by no panel, so the promise is false and the numbers are "
                        + "invisible to anyone operating the deployment:\n  "
                        + String.join("\n  ", uncharted)
                        + "\n\nAdd a panel to " + FULL_DASHBOARD
                        + ", or justify the omission in INTENTIONALLY_UNCHARTED.");
    }

    @Test
    @DisplayName("every registered eddi meter is described in docs/metrics.md")
    void everyRegisteredMeterIsDocumented() {
        Path root = repoRoot();
        String reference = read(root.resolve(METRICS_REFERENCE));

        var undescribed = new TreeSet<String>();
        for (var meter : collectMeters(root).values()) {
            if (!reference.contains(meter.exposedAs())) {
                undescribed.add(meter.describe());
            }
        }

        assertTrue(undescribed.isEmpty(),
                "docs/metrics.md is the metrics reference. A meter missing from it is one an operator "
                        + "can only find by reading the source or scrolling a Grafana dropdown:\n  "
                        + String.join("\n  ", undescribed));
    }

    @Test
    @DisplayName("every query on every dashboard names a series a scrape can contain")
    void everyQueryNamesASeriesThatExists() {
        Path root = repoRoot();
        Set<String> known = knownSeries(root);
        var unknown = new TreeSet<String>();
        for (Path file : dashboards(root)) {
            JsonNode dashboard = dashboard(file);
            List<String> queries = new ArrayList<>(expressions(dashboard));
            for (JsonNode variable : dashboard.path("templating").path("list")) {
                if (variable.has("definition")) {
                    queries.add(variable.path("definition").asText());
                }
            }
            for (JsonNode annotation : dashboard.path("annotations").path("list")) {
                if (annotation.has("expr")) {
                    queries.add(annotation.path("expr").asText());
                }
            }
            for (String query : queries) {
                for (String metric : metricNames(query)) {
                    if (!known.contains(metric)) {
                        unknown.add(file.getFileName() + ": " + metric + "   in   " + query.replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertTrue(unknown.isEmpty(),
                "These dashboard queries name series that no EDDI meter and no Quarkus/Micrometer built-in produces, "
                        + "so the panel reads \"No data\" forever — indistinguishable from an idle system. A counter is "
                        + "scraped with _total, a timer with _seconds_{count,sum,max} (and _bucket only when it publishes "
                        + "a histogram). A genuinely new built-in goes into MonitoringSeries.BUILT_IN:\n  "
                        + String.join("\n  ", unknown));
    }

    @Test
    @DisplayName("panel ids are unique within each dashboard")
    void panelIdsAreUnique() {
        Path root = repoRoot();
        var duplicates = new TreeSet<String>();
        for (Path file : dashboards(root)) {
            Map<Integer, String> seen = new HashMap<>();
            for (JsonNode panel : panels(dashboard(file))) {
                int id = panel.path("id").asInt(-1);
                String title = panel.path("title").asText();
                String previous = seen.putIfAbsent(id, title);
                if (previous != null) {
                    duplicates.add(file.getFileName() + ": id " + id + " is both '" + previous + "' and '" + title + "'");
                }
            }
        }
        assertTrue(duplicates.isEmpty(),
                "Grafana keys panel state (viewPanel links, library panels, repeated rows) by id; a duplicate makes "
                        + "one of the two panels unreachable:\n  " + String.join("\n  ", duplicates));
    }

    /**
     * The operations dashboard hard-coded {@code job="eddi"} and datasource uid
     * {@code prometheus}. Imported anywhere the datasource had another uid — the
     * Kubernetes component provisioned it without one — every panel failed with
     * "datasource not found"; scraped under another job name, every panel was
     * empty.
     */
    @Test
    @DisplayName("every dashboard picks its datasource and scrape job through variables")
    void dashboardsAreNotPinnedToOneDeployment() {
        Path root = repoRoot();
        var problems = new ArrayList<String>();
        for (Path file : dashboards(root)) {
            JsonNode dashboard = dashboard(file);
            var variables = new TreeSet<String>();
            for (JsonNode variable : dashboard.path("templating").path("list")) {
                variables.add(variable.path("name").asText());
                if ("job".equals(variable.path("name").asText())) {
                    String definition = variable.path("definition").asText();
                    if (!definition.contains("process_uptime_seconds")) {
                        problems.add(file.getFileName() + ": $job is sourced from '" + definition
                                + "' — use process_uptime_seconds, a series every JVM target has");
                    }
                }
            }
            if (!variables.contains("datasource")) {
                problems.add(file.getFileName() + ": no $datasource variable");
            }
            for (JsonNode panel : panels(dashboard)) {
                Stream.concat(Stream.of(panel), stream(panel.path("targets"))).forEach(node -> {
                    JsonNode uid = node.path("datasource").path("uid");
                    if (!uid.isMissingNode() && !"${datasource}".equals(uid.asText())) {
                        problems.add(file.getFileName() + ": panel '" + panel.path("title").asText()
                                + "' pins datasource uid '" + uid.asText() + "'");
                    }
                });
            }
            if (variables.contains("job")) {
                for (String expr : expressions(dashboard)) {
                    if (expr.contains("job=\"")) {
                        problems.add(file.getFileName() + ": hard-coded job in " + expr);
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * A KPI over a rate or an increase reads "No data" whenever nothing happened in
     * the window — and, for a ratio, whenever the denominator's other half has
     * never been registered: "Tool success %" was blank on a healthy system until
     * the first tool ever failed. Each such stat or gauge must either fall back to
     * zero ({@code or on() vector(0)}) or say why it is empty ({@code noValue}).
     */
    @Test
    @DisplayName("KPI panels over rates say 0 or say why, instead of \"No data\"")
    void kpiPanelsDoNotGoBlankOnAnIdleSystem() {
        Path root = repoRoot();
        var blank = new TreeSet<String>();
        for (Path file : dashboards(root)) {
            for (JsonNode panel : panels(dashboard(file))) {
                String type = panel.path("type").asText();
                if (!type.equals("stat") && !type.equals("gauge")) {
                    continue;
                }
                boolean hasNoValue = panel.path("fieldConfig").path("defaults").hasNonNull("noValue");
                for (JsonNode target : panel.path("targets")) {
                    String expr = target.path("expr").asText();
                    boolean overEvents = expr.contains("rate(") || expr.contains("increase(");
                    if (overEvents && !expr.contains("vector(") && !hasNoValue) {
                        blank.add(file.getFileName() + ": '" + panel.path("title").asText() + "'  " + expr);
                    }
                }
            }
        }
        assertTrue(blank.isEmpty(), "These KPIs read \"No data\" on a quiet system:\n  " + String.join("\n  ", blank));
    }

    @Test
    @DisplayName("the Full Metrics Reference opens on its Overview row")
    void overviewComesFirst() {
        JsonNode first = dashboard(repoRoot().resolve(FULL_DASHBOARD)).path("panels").get(0);
        assertEquals("row", first.path("type").asText());
        assertTrue(first.path("title").asText().startsWith("Overview"),
                "the first row is '" + first.path("title").asText() + "'; the 'LLM — Per-call' row used to sit above Overview");
    }

    // ---------------------------------------------------------------- helpers

    static List<Path> dashboards(Path root) {
        try (Stream<Path> files = Files.list(root.resolve(MONITORING))) {
            List<Path> found = files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList();
            assertTrue(found.size() >= 3, "expected the three shipped dashboards under " + MONITORING + ", found " + found);
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode dashboard(Path file) {
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(file + " is not valid JSON", e);
        }
    }

    /** Every panel, including those nested in collapsed rows. */
    static List<JsonNode> panels(JsonNode dashboard) {
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode panel : dashboard.path("panels")) {
            all.add(panel);
            panel.path("panels").forEach(all::add);
        }
        return all;
    }

    static List<String> expressions(JsonNode dashboard) {
        List<String> all = new ArrayList<>();
        for (JsonNode panel : panels(dashboard)) {
            for (JsonNode target : panel.path("targets")) {
                if (target.hasNonNull("expr")) {
                    all.add(target.path("expr").asText());
                }
            }
        }
        return all;
    }

    private static Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> list = new ArrayList<>();
        array.forEach(list::add);
        return list.stream();
    }
}
