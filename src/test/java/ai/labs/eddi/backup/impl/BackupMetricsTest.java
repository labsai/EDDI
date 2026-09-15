/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The counters an operator alerts on when an export or a sync "did nothing".
 * <p>
 * Two things are asserted here and nowhere else: that each method moves the
 * meter with the exact name the dashboards and {@code docs/metrics.md} query —
 * a renamed or mistyped meter is invisible until an alert fails to fire — and
 * that a {@code BackupMetrics} whose {@code @PostConstruct} never ran (every
 * unit test that builds the backup services directly) records nothing instead
 * of throwing, because metrics must never be the reason a backup fails.
 */
@DisplayName("BackupMetrics")
class BackupMetricsTest {

    private MeterRegistry registry;
    private BackupMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BackupMetrics(registry);
        metrics.initMetrics();
    }

    private double count(String name) {
        var counter = registry.find(name).counter();
        return counter == null ? -1d : counter.count();
    }

    @Nested
    @DisplayName("with the registry wired up")
    class Wired {

        /**
         * Export attempts and failures are separate meters, each counted once per call.
         */
        @Test
        @DisplayName("export attempts and failures land on their own meters")
        void exportCounters() {
            metrics.exportAttempted();
            metrics.exportAttempted();
            metrics.exportFailed();

            assertEquals(2.0, count("eddi.backup.export.count"));
            assertEquals(1.0, count("eddi.backup.export.failure.count"));
        }

        /**
         * Import attempts and failures are separate meters, each counted once per call.
         */
        @Test
        @DisplayName("import attempts and failures land on their own meters")
        void importCounters() {
            metrics.importAttempted();
            metrics.importFailed();
            metrics.importFailed();

            assertEquals(1.0, count("eddi.backup.import.count"));
            assertEquals(2.0, count("eddi.backup.import.failure.count"));
        }

        /**
         * Upgrade attempts and failures are separate meters, each counted once per
         * call.
         */
        @Test
        @DisplayName("upgrade attempts and failures land on their own meters")
        void upgradeCounters() {
            metrics.upgradeAttempted();
            metrics.upgradeFailed();

            assertEquals(1.0, count("eddi.backup.upgrade.count"));
            assertEquals(1.0, count("eddi.backup.upgrade.failure.count"));
        }

        /**
         * The per-resource tally is what answers "the sync did nothing" — each of the
         * four outcomes must move its own meter by its own amount, never by one.
         */
        @Test
        @DisplayName("upgradeCompleted adds each per-resource tally to its own meter")
        void upgradeCompletedTallies() {
            metrics.upgradeCompleted(3, 2, 5, 1);

            assertEquals(3.0, count("eddi.backup.upgrade.resource.updated.count"));
            assertEquals(2.0, count("eddi.backup.upgrade.resource.created.count"));
            assertEquals(5.0, count("eddi.backup.upgrade.resource.skipped.count"));
            assertEquals(1.0, count("eddi.backup.upgrade.resource.failure.count"));
        }

        /**
         * A run that skipped everything must not touch the other three meters at all.
         * <p>
         * Reading the count back cannot show this — {@code increment(0)} and no call
         * leave a counter reading 0.0 alike — so the increments themselves are
         * recorded, and the assertion is on the call, not on the total. What it
         * protects is the meter's own semantics: Micrometer counters are monotonic and
         * a registry backend is entitled to reject or to emit a sample for any
         * increment it is handed, so "nothing happened" has to mean no increment
         * reached the registry.
         */
        @Test
        @DisplayName("a zero tally never reaches its counter")
        void upgradeCompletedIgnoresZeroTallies() {
            var recordingRegistry = new RecordingRegistry();
            var recordingMetrics = new BackupMetrics(recordingRegistry);
            recordingMetrics.initMetrics();

            recordingMetrics.upgradeCompleted(0, 0, 4, 0);

            assertEquals(List.of(4.0), recordingRegistry.incrementsOf("eddi.backup.upgrade.resource.skipped.count"));
            assertEquals(List.of(), recordingRegistry.incrementsOf("eddi.backup.upgrade.resource.updated.count"));
            assertEquals(List.of(), recordingRegistry.incrementsOf("eddi.backup.upgrade.resource.created.count"));
            assertEquals(List.of(), recordingRegistry.incrementsOf("eddi.backup.upgrade.resource.failure.count"));
        }

        /**
         * A negative tally cannot happen through {@code UpgradeResult}, but Micrometer
         * counters are monotonic and would reject one — the guard keeps that from
         * reaching the registry.
         */
        @Test
        @DisplayName("a negative tally is dropped rather than rejected by the registry")
        void upgradeCompletedIgnoresNegativeTallies() {
            metrics.upgradeCompleted(-5, 0, 0, 0);

            assertEquals(0.0, count("eddi.backup.upgrade.resource.updated.count"));
        }
    }

    @Nested
    @DisplayName("before @PostConstruct has run")
    class Uninitialized {

        /**
         * Every unit test in this package builds the backup services with a
         * {@code BackupMetrics} CDI never initialized. Each counter method must be a
         * no-op then, not a NullPointerException that fails the operation it measures.
         */
        @Test
        @DisplayName("every counter method is a silent no-op")
        void uninitializedCountersDoNotThrow() {
            var uninitialized = new BackupMetrics(new SimpleMeterRegistry());

            assertDoesNotThrow(() -> {
                uninitialized.exportAttempted();
                uninitialized.exportFailed();
                uninitialized.importAttempted();
                uninitialized.importFailed();
                uninitialized.upgradeAttempted();
                uninitialized.upgradeFailed();
                uninitialized.upgradeCompleted(1, 1, 1, 1);
            });
        }

        /**
         * Nothing was registered either — the meters only exist once initMetrics ran.
         */
        @Test
        @DisplayName("registers no meters")
        void uninitializedRegistersNothing() {
            var ownRegistry = new SimpleMeterRegistry();
            var uninitialized = new BackupMetrics(ownRegistry);

            uninitialized.exportAttempted();

            assertNull(ownRegistry.find("eddi.backup.export.count").counter());
        }
    }

    // ==================== Helpers ====================

    /**
     * A real registry that also remembers every increment its counters were handed.
     * <p>
     * The hook is the registry's own counter factory rather than
     * {@code MeterRegistry.counter(String)}, so it keeps working whichever idiom
     * registers the meter — the one-arg call, a tagged {@code counter(name, tags)},
     * or {@code Counter.builder(name).register(registry)}. A mock registry stubbed
     * for one overload would quietly record nothing after such a refactor and turn
     * the assertions below into no-ops.
     */
    private static final class RecordingRegistry extends SimpleMeterRegistry {

        private final Map<String, List<Double>> increments = new LinkedHashMap<>();

        @Override
        protected Counter newCounter(Meter.Id id) {
            Counter delegate = super.newCounter(id);
            List<Double> recorded = increments.computeIfAbsent(id.getName(), name -> new ArrayList<>());
            return new Counter() {
                @Override
                public void increment(double amount) {
                    recorded.add(amount);
                    delegate.increment(amount);
                }

                @Override
                public double count() {
                    return delegate.count();
                }

                @Override
                public Id getId() {
                    return id;
                }
            };
        }

        /** Every amount that reached the named counter, in order. */
        List<Double> incrementsOf(String meterName) {
            List<Double> recorded = increments.get(meterName);
            assertNotNull(recorded, "no counter named '" + meterName
                    + "' was ever registered, only: " + increments.keySet());
            return recorded;
        }
    }
}
