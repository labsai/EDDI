/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@code /q/metrics} can actually contain, for the guards that check the
 * dashboards and the alert rules against it.
 * <p>
 * Two sources: the meters EDDI registers (read from their registration sites in
 * {@code src/main/java}, with the suffixes Micrometer's Prometheus registry
 * appends), and the series Quarkus and Micrometer contribute on their own,
 * listed in {@link #BUILT_IN}. A query that names anything else names a series
 * that does not exist, and renders "No data" forever — which is how
 * {@code eddi_nats_dead_letter_count} (no {@code _total}) and {@code jvm_info}
 * (exposed as {@code jvm_info_total}) went unnoticed.
 */
final class MonitoringSeries {

    private MonitoringSeries() {
    }

    /**
     * Meter registration through a
     * {@link io.micrometer.core.instrument.MeterRegistry} handle, or through a
     * local helper that forwards to one. The receiver is deliberately unanchored:
     * registrations go through {@code meterRegistry},
     * {@code Metrics.globalRegistry} and private {@code increment(...)} helpers
     * alike, and the name is the first argument in every case. Group 1 is the meter
     * <em>type</em>, which decides the exposition suffix.
     * <p>
     * The name group is deliberately <em>not</em> anchored on {@code eddi}. It was,
     * and fourteen meters across four subsystems — every Dream,
     * connection-resolution, summarization and guardrail meter — simply did not
     * match, so the guard that exists to make an unwatched meter impossible could
     * not see them.
     */
    private static final Pattern REGISTRATION = Pattern.compile(
            "(?:^|[^\\w])(counter|timer|gauge|summary|increment)\\s*\\(\\s*\"([a-z][\\w.]*)\"");

    /**
     * The builder form, e.g.
     * {@code FunctionCounter.builder("eddi.coordinator.total_processed", …)}.
     */
    private static final Pattern BUILDER = Pattern.compile(
            "(Counter|Timer|Gauge|FunctionCounter|DistributionSummary)\\.builder\\(\\s*\"([a-z][\\w.]*)\"");

    /**
     * Both forms with the name held in a constant of the same file, e.g.
     * {@code meterRegistry.gauge(ACCRUED_COST_GAUGE, …)}.
     * {@code eddi.tool.costs.accrued} is registered that way, and the string
     * patterns above never saw it: it was the one meter both the old coverage check
     * and this one would have called unregistered.
     */
    private static final Pattern REGISTRATION_BY_CONSTANT = Pattern.compile(
            "(?:^|[^\\w])(counter|timer|gauge|summary|increment)\\s*\\(\\s*([A-Z][A-Z0-9_]*)\\s*[,)]");
    private static final Pattern BUILDER_BY_CONSTANT = Pattern.compile(
            "(Counter|Timer|Gauge|FunctionCounter|DistributionSummary)\\.builder\\(\\s*([A-Z][A-Z0-9_]*)\\s*[,)]");
    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "static\\s+final\\s+String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"([a-z][\\w.]*)\"");

    /**
     * Series Quarkus and Micrometer register without any EDDI code, verified
     * against a live 6.5.0 scrape (MongoDB datastore) and, for {@code agroal_*},
     * the PostgreSQL datastore with {@code quarkus.datasource.metrics.enabled}.
     * Only what the dashboards and rules use is listed: adding a name here is a
     * claim that it is on a real scrape.
     */
    static final Set<String> BUILT_IN = Set.of(
            "up",
            "http_server_requests_seconds_count", "http_server_requests_seconds_sum", "http_server_requests_seconds_max",
            "jvm_memory_used_bytes", "jvm_memory_committed_bytes", "jvm_memory_max_bytes", "jvm_memory_usage_after_gc",
            "jvm_threads_live_threads", "jvm_threads_daemon_threads", "jvm_threads_peak_threads",
            "jvm_gc_pause_seconds_count", "jvm_gc_pause_seconds_sum", "jvm_gc_pause_seconds_max",
            "process_uptime_seconds", "process_start_time_seconds", "process_cpu_usage", "system_cpu_usage",
            "worker_pool_ratio", "worker_pool_queue_size", "worker_pool_active",
            // Micrometer's MongoMetricsConnectionPoolListener, added in PersistenceModule.
            "mongodb_driver_pool_size", "mongodb_driver_pool_checkedout", "mongodb_driver_pool_waitqueuesize",
            // Agroal, quarkus.datasource.metrics.enabled=true.
            "agroal_active_count", "agroal_available_count", "agroal_awaiting_count", "agroal_max_used_count");

    /** Prefixes that mark an identifier in a query as a metric name. */
    private static final List<String> METRIC_PREFIXES = List.of("eddi_", "http_", "jvm_", "process_", "system_", "mongodb_",
            "agroal_", "worker_pool_", "netty_");

    private static final Pattern IDENTIFIER = Pattern.compile("(?<![\\w$.\"])([a-zA-Z_:][a-zA-Z0-9_:]*)");

    /** A registered meter, and the name it is actually scraped under. */
    record Meter(String name, String type, String source) {

        String exposedAs() {
            return expositionName(name, type);
        }

        /** Every series this meter puts on the scrape. */
        List<String> series() {
            String base = name.replace('.', '_');
            return switch (type) {
                case "timer" -> List.of(base + "_seconds_count", base + "_seconds_sum", base + "_seconds_max",
                        base + "_seconds_bucket");
                case "summary" -> List.of(base + "_count", base + "_sum", base + "_max", base + "_bucket");
                default -> List.of(exposedAs());
            };
        }

        String describe() {
            return String.format("%s (%s, scraped as %s, registered in %s)", name, type, exposedAs(), source);
        }
    }

    /**
     * The name a meter is actually scraped under, which is what a dashboard query
     * and a documentation table have to name.
     * <p>
     * Micrometer's Prometheus exposition appends {@code _total} to counters and
     * {@code _seconds} to timers, and leaves gauges alone. A handful of meters are
     * registered in snake_case with {@code _total} already in the name (e.g.
     * {@code eddi_audit_entries_dropped_total}); those must not have a second one
     * appended.
     */
    static String expositionName(String meter, String type) {
        String base = meter.replace('.', '_');
        return switch (type) {
            case "counter" -> base.endsWith("_total") ? base : base + "_total";
            case "timer" -> base + "_seconds";
            default -> base;
        };
    }

    /** Meter name → what is known about it. */
    static TreeMap<String, Meter> collectMeters(Path root) {
        var found = new TreeMap<String, Meter>();
        for (Path file : javaSources(root.resolve(Path.of("src", "main", "java")))) {
            String body = read(file);
            String relative = root.relativize(file).toString().replace('\\', '/');
            record(REGISTRATION.matcher(body), found, relative, null);
            record(BUILDER.matcher(body), found, relative, null);
            Map<String, String> constants = new HashMap<>();
            Matcher constant = STRING_CONSTANT.matcher(body);
            while (constant.find()) {
                constants.put(constant.group(1), constant.group(2));
            }
            record(REGISTRATION_BY_CONSTANT.matcher(body), found, relative, constants);
            record(BUILDER_BY_CONSTANT.matcher(body), found, relative, constants);
        }
        assertTrue(found.size() > 100,
                "expected to find the project's meter registrations; found only " + found.size()
                        + ". The extraction patterns have probably drifted from how meters are registered.");
        return found;
    }

    /** Every series name a scrape of this codebase can contain. */
    static Set<String> knownSeries(Path root) {
        var known = new TreeSet<>(BUILT_IN);
        for (Meter meter : collectMeters(root).values()) {
            known.addAll(meter.series());
        }
        return known;
    }

    /**
     * The metric names a PromQL expression reads. Identifiers are taken as metric
     * names when they carry a metric prefix ({@code eddi_}, {@code jvm_}, …) or are
     * {@code up}; label names never do, so {@code by (task_type)} is not mistaken
     * for one.
     */
    static Set<String> metricNames(String expr) {
        var names = new TreeSet<String>();
        String withoutStrings = expr.replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
        Matcher matcher = IDENTIFIER.matcher(withoutStrings);
        while (matcher.find()) {
            String token = matcher.group(1);
            if (token.equals("up") || METRIC_PREFIXES.stream().anyMatch(token::startsWith)) {
                names.add(token);
            }
        }
        return names;
    }

    /**
     * @param constants
     *            null when the pattern captures the name itself; otherwise the
     *            file's string constants, and a captured identifier that is not one
     *            of them is not a meter name
     */
    private static void record(Matcher matcher, TreeMap<String, Meter> into, String source, Map<String, String> constants) {
        while (matcher.find()) {
            String type = matcher.group(1).toLowerCase(Locale.ROOT);
            String name = constants == null ? matcher.group(2) : constants.get(matcher.group(2));
            if (name == null) {
                continue;
            }
            // increment(...) is a local helper that forwards to counter(...);
            // FunctionCounter and DistributionSummary expose as counter/summary.
            String normalised = switch (type) {
                case "increment", "functioncounter" -> "counter";
                case "distributionsummary" -> "summary";
                default -> type;
            };
            into.putIfAbsent(name, new Meter(name, normalised, source));
        }
    }

    static Path repoRoot() {
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

    static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
