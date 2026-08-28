/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the Full Metrics Reference dashboard honest about covering every meter.
 * <p>
 * {@code docs/metrics.md} promises that the dashboard "covers all registered
 * meters — it is generated from the registration sites in the source, so a
 * metric cannot be added to the codebase and silently go unwatched". That was a
 * description of intent, not of a mechanism: nothing generated the file and
 * nothing checked it. By the time the claim was audited it was already false —
 * the five {@code eddi.llm.cascade.*} decision counters ({@code executions},
 * {@code escalations}, {@code accepted.step}, {@code step.errors},
 * {@code ceiling.exceeded}) were registered and on no panel. Those are
 * precisely the meters that say whether model cascading is saving money or
 * silently paying twice per turn, so the gap sat over the one subsystem whose
 * value cannot be judged without them.
 * <p>
 * A promise about coverage is only worth making if something enforces it. This
 * test is that something: add a meter, and the build tells you the dashboard
 * has not caught up.
 *
 * @see ai.labs.eddi.docs.DocumentedRestPathsTest
 */
@DisplayName("metrics dashboard coverage")
class MetricsDashboardCoverageTest {

    /**
     * Meter registration through a
     * {@link io.micrometer.core.instrument.MeterRegistry} handle, or through a
     * local helper that forwards to one. The receiver is deliberately unanchored:
     * registrations go through {@code meterRegistry},
     * {@code Metrics.globalRegistry} and private {@code increment(...)} helpers
     * alike, and the name is the first argument in every case.
     */
    private static final Pattern REGISTRATION = Pattern.compile(
            "(?:counter|timer|gauge|summary|increment)\\s*\\(\\s*\"(eddi[._][\\w.]+)\"");

    /**
     * The builder form, e.g.
     * {@code FunctionCounter.builder("eddi.coordinator.total_processed", …)}.
     */
    private static final Pattern BUILDER = Pattern.compile(
            "(?:Counter|Timer|Gauge|FunctionCounter|DistributionSummary)\\.builder\\(\\s*\"(eddi[._][\\w.]+)\"");

    private static final Path DASHBOARD = Path.of("docs", "monitoring", "eddi-full-metrics-dashboard.json");

    private static final Path METRICS_REFERENCE = Path.of("docs", "metrics.md");

    /**
     * Meters that are registered but deliberately not on the dashboard, as
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
        var registered = collectMeters(root);

        // Prometheus renders '.' as '_', so compare in that spelling. Suffixes
        // (_total, _seconds_sum, …) are appended by the exporter, so a prefix
        // match is the correct test, not equality.
        String dashboard = read(root.resolve(DASHBOARD)).replace('.', '_');

        var uncharted = new TreeSet<String>();
        for (var entry : registered.entrySet()) {
            String meter = entry.getKey();
            if (INTENTIONALLY_UNCHARTED.contains(meter)) {
                continue;
            }
            if (!dashboard.contains(meter.replace('.', '_'))) {
                uncharted.add(String.format("%s (registered in %s)", meter, entry.getValue()));
            }
        }

        assertTrue(uncharted.isEmpty(),
                "docs/metrics.md promises the Full Metrics Reference covers every registered meter. "
                        + "These are registered and on no panel, so the promise is false and the numbers are "
                        + "invisible to anyone operating the deployment:\n  "
                        + String.join("\n  ", uncharted)
                        + "\n\nAdd a panel to " + DASHBOARD
                        + ", or justify the omission in INTENTIONALLY_UNCHARTED.");
    }

    @Test
    @DisplayName("every registered eddi meter is described in docs/metrics.md")
    void everyRegisteredMeterIsDocumented() {
        Path root = repoRoot();
        var registered = collectMeters(root);

        // The reference writes meters in the Prometheus spelling, so compare
        // there. Exporter suffixes (_total, _seconds_sum, …) are appended to the
        // documented name, which a containment check handles.
        String reference = read(root.resolve(METRICS_REFERENCE)).replace('.', '_');

        var undescribed = new TreeSet<String>();
        for (var entry : registered.entrySet()) {
            if (!reference.contains(entry.getKey().replace('.', '_'))) {
                undescribed.add(String.format("%s (registered in %s)", entry.getKey(), entry.getValue()));
            }
        }

        assertTrue(undescribed.isEmpty(),
                "docs/metrics.md is the metrics reference. A meter missing from it is one an operator "
                        + "can only find by reading the source or scrolling a Grafana dropdown:\n  "
                        + String.join("\n  ", undescribed));
    }

    private static void record(Matcher matcher, TreeMap<String, String> into, String source) {
        while (matcher.find()) {
            into.putIfAbsent(matcher.group(1), source);
        }
    }

    /** Meter name → the source file that registers it. */
    private static TreeMap<String, String> collectMeters(Path root) {
        var found = new TreeMap<String, String>();
        for (Path file : javaSources(root.resolve(Path.of("src", "main", "java")))) {
            String body = read(file);
            String relative = root.relativize(file).toString().replace('\\', '/');
            record(REGISTRATION.matcher(body), found, relative);
            record(BUILDER.matcher(body), found, relative);
        }
        assertTrue(found.size() > 100,
                "expected to find the project's meter registrations; found only " + found.size()
                        + ". The extraction patterns have probably drifted from how meters are registered.");
        return found;
    }

    private static Path repoRoot() {
        // Surefire runs with the project basedir as the working directory.
        Path root = Path.of("").toAbsolutePath();
        assertTrue(Files.isRegularFile(root.resolve("pom.xml")),
                "expected the working directory to be the project root, was " + root);
        return root;
    }

    private static List<Path> javaSources(Path base) {
        List<Path> found = new ArrayList<>();
        try {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                        found.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
