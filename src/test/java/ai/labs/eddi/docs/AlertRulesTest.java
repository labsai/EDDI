/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.labs.eddi.docs.MonitoringSeries.knownSeries;
import static ai.labs.eddi.docs.MonitoringSeries.metricNames;
import static ai.labs.eddi.docs.MonitoringSeries.read;
import static ai.labs.eddi.docs.MonitoringSeries.repoRoot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shipped Prometheus alert rules: well-formed, loaded, documented, and
 * about series that exist.
 * <p>
 * Until 6.5.0 no rule was shipped or loaded anywhere — the monitoring guide
 * carried a sample block for operators to paste, two of whose six rules named
 * series that do not exist. This guards the structure and the wiring without
 * cluster tooling. CI additionally runs {@code promtool check rules} and the
 * behavioural cases in {@code docs/monitoring/eddi-alerts.test.yml} (which fire
 * and silence each essential rule against synthetic series).
 *
 * @see MonitoringSeries
 */
@DisplayName("prometheus alert rules")
class AlertRulesTest {

    private static final YAMLMapper YAML = new YAMLMapper();

    private static final Path RULES = Path.of("docs", "monitoring", "eddi-alerts.yml");
    private static final Path RULE_TESTS = Path.of("docs", "monitoring", "eddi-alerts.test.yml");
    private static final Path GUIDE = Path.of("docs", "monitoring", "monitoring-guide.md");
    private static final Path PROMETHEUS_CONFIG = Path.of("docs", "monitoring", "prometheus.yml");
    private static final Path COMPOSE = Path.of("docker-compose.monitoring.yml");

    private static final Set<String> SEVERITIES = Set.of("critical", "warning", "info");

    private static final String RUNBOOK_PREFIX = "https://github.com/labsai/EDDI/blob/main/docs/monitoring/monitoring-guide.md#";

    /**
     * The essentials REVIEW-2026-10-02 §4.9 asked for, by the alert that covers
     * each. Removing one is a decision to stop watching that failure.
     */
    private static final Set<String> ESSENTIAL = Set.of(
            "EddiDown", // up == 0
            "EddiHighHttp5xxRatio", // 5xx ratio
            "EddiCoordinatorQueueBacklog", // coordinator queue depth
            "EddiAuditEntriesDropped", "EddiAuditSequenceCollisions", // audit dropped / collisions
            "EddiPipelineTaskErrors", // pipeline errors
            "EddiLlmErrorRatio", "EddiLlmLatencyHigh", // LLM error ratio and p95
            "EddiToolFailureRatio", // tool failure ratio
            "EddiVaultErrors", // vault errors
            "EddiQuotaStoreUnavailable", // quota unavailable
            "EddiScheduleFiresDeadLettered", "EddiNatsDeadLetters", // schedule / NATS dead letters
            "EddiOperatorGateRegressed", // gate verified
            "EddiHeapAfterGcHigh", // heap after GC
            "EddiRestartLoop"); // restart loop

    private static final Pattern DURATION = Pattern.compile("\\d+[smhdwy]");

    private static final Pattern HEADING = Pattern.compile("(?m)^#{2,6}\\s+(.+?)\\s*$");

    record Rule(String group, JsonNode node) {

        String name() {
            return node.path("alert").asText();
        }

        String expr() {
            return node.path("expr").asText();
        }
    }

    private static List<Rule> rules() {
        try {
            JsonNode root = YAML.readTree(repoRoot().resolve(RULES).toFile());
            List<Rule> rules = new ArrayList<>();
            for (JsonNode group : root.path("groups")) {
                for (JsonNode rule : group.path("rules")) {
                    rules.add(new Rule(group.path("name").asText(), rule));
                }
            }
            assertTrue(rules.size() >= ESSENTIAL.size(), "parsed only " + rules.size() + " rules from " + RULES);
            return rules;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("every rule is a complete alerting rule")
    void everyRuleIsWellFormed() {
        var problems = new ArrayList<String>();
        var names = new TreeSet<String>();
        for (Rule rule : rules()) {
            String id = rule.group() + "/" + rule.name();
            if (rule.name().isBlank()) {
                problems.add(rule.group() + ": a rule without `alert:` (recording rules belong elsewhere)");
            }
            if (!names.add(rule.name())) {
                problems.add(id + ": duplicate alert name");
            }
            if (rule.expr().isBlank()) {
                problems.add(id + ": no expr");
            }
            if (rule.node().has("for") && !DURATION.matcher(rule.node().path("for").asText()).matches()) {
                problems.add(id + ": `for: " + rule.node().path("for").asText() + "` is not a Prometheus duration");
            }
            String severity = rule.node().path("labels").path("severity").asText();
            if (!SEVERITIES.contains(severity)) {
                problems.add(id + ": severity '" + severity + "' is not one of " + SEVERITIES);
            }
            for (String annotation : List.of("summary", "description", "runbook_url")) {
                if (rule.node().path("annotations").path(annotation).asText().isBlank()) {
                    problems.add(id + ": no " + annotation + " annotation");
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    @DisplayName("the essentials from the 6.5.0 review are all covered")
    void essentialsAreCovered() {
        var present = new TreeSet<String>();
        rules().forEach(rule -> present.add(rule.name()));
        var missing = new TreeSet<>(ESSENTIAL);
        missing.removeAll(present);
        assertTrue(missing.isEmpty(), "no rule for: " + missing);
    }

    @Test
    @DisplayName("every rule reads series a scrape can contain")
    void everyRuleReadsSeriesThatExist() {
        Set<String> known = knownSeries(repoRoot());
        var unknown = new TreeSet<String>();
        for (Rule rule : rules()) {
            for (String metric : metricNames(rule.expr())) {
                if (!known.contains(metric)) {
                    unknown.add(rule.name() + ": " + metric);
                }
            }
        }
        assertTrue(unknown.isEmpty(),
                "These rules name series that nothing registers, so they can never fire:\n  " + String.join("\n  ", unknown));
    }

    /**
     * eddi_* series are unique to EDDI and need no job selector. Generic ones are
     * not: {@code up == 0} with no selector pages for every target the Prometheus
     * scrapes.
     */
    @Test
    @DisplayName("rules over generic series select the eddi job")
    void genericSeriesAreScopedToEddi() {
        var unscoped = new TreeSet<String>();
        for (Rule rule : rules()) {
            boolean generic = metricNames(rule.expr()).stream().anyMatch(m -> !m.startsWith("eddi_"));
            if (generic && !rule.expr().contains("job=\"eddi\"")) {
                unscoped.add(rule.name());
            }
        }
        assertTrue(unscoped.isEmpty(), "rules over up/http_*/jvm_*/process_* without job=\"eddi\": " + unscoped);
    }

    @Test
    @DisplayName("every runbook link lands on a heading of the monitoring guide")
    void runbooksExist() {
        String guide = read(repoRoot().resolve(GUIDE));
        var anchors = new TreeSet<String>();
        Matcher heading = HEADING.matcher(guide);
        while (heading.find()) {
            anchors.add(slug(heading.group(1)));
        }
        var broken = new TreeSet<String>();
        for (Rule rule : rules()) {
            String url = rule.node().path("annotations").path("runbook_url").asText();
            if (!url.startsWith(RUNBOOK_PREFIX) || !anchors.contains(url.substring(RUNBOOK_PREFIX.length()))) {
                broken.add(rule.name() + " -> " + url);
            }
            if (!url.endsWith("#" + rule.name().toLowerCase(Locale.ROOT))) {
                broken.add(rule.name() + " -> " + url + " (expected the runbook heading to be the alert name)");
            }
        }
        assertTrue(broken.isEmpty(), "runbook_url must point at a heading in " + GUIDE + ":\n  " + String.join("\n  ", broken));
    }

    /**
     * The rules shipped and were loaded nowhere: Prometheus only evaluates a file
     * named under {@code rule_files}, and the compose stack only sees what it
     * mounts.
     */
    @Test
    @DisplayName("the compose monitoring stack mounts and loads the rules")
    void composeStackLoadsTheRules() throws IOException {
        JsonNode config = YAML.readTree(repoRoot().resolve(PROMETHEUS_CONFIG).toFile());
        List<String> ruleFiles = new ArrayList<>();
        config.path("rule_files").forEach(f -> ruleFiles.add(f.asText()));
        assertEquals(List.of("/etc/prometheus/eddi-alerts.yml"), ruleFiles);

        JsonNode compose = YAML.readTree(repoRoot().resolve(COMPOSE).toFile());
        List<String> mounts = new ArrayList<>();
        compose.path("services").path("prometheus").path("volumes").forEach(v -> mounts.add(v.asText()));
        assertTrue(mounts.contains("./docs/monitoring/eddi-alerts.yml:/etc/prometheus/eddi-alerts.yml:ro"),
                "docker-compose.monitoring.yml must mount the rules where rule_files expects them: " + mounts);

        // Both installers download every file the overlay bind-mounts; a missing
        // source makes Docker create a DIRECTORY there and Prometheus refuses to start.
        for (String installer : List.of("install.sh", "install.ps1")) {
            assertTrue(read(repoRoot().resolve(installer)).contains("\"docs/monitoring/eddi-alerts.yml\""),
                    installer + " must download docs/monitoring/eddi-alerts.yml for --with-monitoring");
        }
        assertTrue(read(repoRoot().resolve(RULE_TESTS)).contains("eddi-alerts.yml"),
                RULE_TESTS + " must test the shipped rule file");
    }

    /**
     * GitHub's heading anchor: lower case, punctuation dropped, spaces to dashes.
     */
    static String slug(String heading) {
        return heading.toLowerCase(Locale.ROOT)
                .replaceAll("`", "")
                .replaceAll("[^a-z0-9 _-]", "")
                .trim()
                .replace(' ', '-');
    }
}
