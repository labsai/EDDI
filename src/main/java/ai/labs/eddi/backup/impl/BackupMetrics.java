/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Counters for the export/import/upgrade flows, exposed at {@code /q/metrics}.
 * <p>
 * They answer the questions an operator asks when a sync "did nothing": the
 * export, import and upgrade attempt and failure rates, and how many resources
 * an upgrade updated, created, skipped or failed on. An upgrade counts every
 * sync and every import with the {@code upgrade} strategy.
 *
 * @since 6.3.0
 */
@ApplicationScoped
public class BackupMetrics {

    private final MeterRegistry meterRegistry;

    private Counter exportAttempts;
    private Counter exportFailures;
    private Counter importAttempts;
    private Counter importFailures;
    private Counter upgradeAttempts;
    private Counter upgradeFailures;
    private Counter upgradeResourcesUpdated;
    private Counter upgradeResourcesCreated;
    private Counter upgradeResourcesSkipped;
    private Counter upgradeResourceFailures;

    @Inject
    public BackupMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void initMetrics() {
        exportAttempts = meterRegistry.counter("eddi.backup.export.count");
        exportFailures = meterRegistry.counter("eddi.backup.export.failure.count");
        importAttempts = meterRegistry.counter("eddi.backup.import.count");
        importFailures = meterRegistry.counter("eddi.backup.import.failure.count");
        upgradeAttempts = meterRegistry.counter("eddi.backup.upgrade.count");
        upgradeFailures = meterRegistry.counter("eddi.backup.upgrade.failure.count");
        upgradeResourcesUpdated = meterRegistry.counter("eddi.backup.upgrade.resource.updated.count");
        upgradeResourcesCreated = meterRegistry.counter("eddi.backup.upgrade.resource.created.count");
        upgradeResourcesSkipped = meterRegistry.counter("eddi.backup.upgrade.resource.skipped.count");
        upgradeResourceFailures = meterRegistry.counter("eddi.backup.upgrade.resource.failure.count");
    }

    public void exportAttempted() {
        increment(exportAttempts);
    }

    public void exportFailed() {
        increment(exportFailures);
    }

    public void importAttempted() {
        increment(importAttempts);
    }

    public void importFailed() {
        increment(importFailures);
    }

    public void upgradeAttempted() {
        increment(upgradeAttempts);
    }

    public void upgradeFailed() {
        increment(upgradeFailures);
    }

    /** Per-resource outcome of one upgrade run. */
    public void upgradeCompleted(int updated, int created, int skipped, int failed) {
        increment(upgradeResourcesUpdated, updated);
        increment(upgradeResourcesCreated, created);
        increment(upgradeResourcesSkipped, skipped);
        increment(upgradeResourceFailures, failed);
    }

    private static void increment(Counter counter) {
        increment(counter, 1);
    }

    /**
     * Counters are null until {@code @PostConstruct} has run, which is the case for
     * an instance built directly in a unit test. Metrics must never be the reason a
     * backup operation fails.
     */
    private static void increment(Counter counter, int amount) {
        if (counter != null && amount > 0) {
            counter.increment(amount);
        }
    }
}
