/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
import ai.labs.eddi.engine.hitl.oversight.samples.IAuditSampleStore;
import ai.labs.eddi.engine.hitl.oversight.samples.SampleFilter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The meters of approval analytics, the sampled audit and earned autonomy, and
 * the per-node refresh of the two gauges. No meter carries an agent or category
 * tag: categories are configurable, so their cardinality is not bounded.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class OversightMetrics {

    private static final Logger LOGGER = Logger.getLogger(OversightMetrics.class);

    /** Counted outside CDI (the gate is a plain class), on the global registry. */
    public static final String SUSPENSION_GATED = "eddi.autonomy.suspension.gated";

    private final MeterRegistry registry;
    private final IAuditSampleStore sampleStore;
    private final IAutonomyStateStore stateStore;
    private final AtomicReference<double[]> gauges = new AtomicReference<>(new double[]{Double.NaN, Double.NaN});

    @Inject
    public OversightMetrics(MeterRegistry registry, IAuditSampleStore sampleStore, IAutonomyStateStore stateStore) {
        this.registry = registry;
        this.sampleStore = sampleStore;
        this.stateStore = stateStore;
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("eddi.audit.sample.pending", gauges, g -> g.get()[0])
                .description("Audit samples waiting for review (store-wide)").register(registry);
        Gauge.builder("eddi.autonomy.suspension.active", gauges, g -> g.get()[1])
                .description("Earned-autonomy categories currently suspended (store-wide)").register(registry);
    }

    @Scheduled(every = "${eddi.hitl.metrics.refresh-interval:60s}", delayed = "20s", identity = "hitl-oversight-metrics",
               concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void refresh() {
        double[] previous = gauges.get();
        try {
            gauges.set(new double[]{sampleStore.count(SampleFilter.pending(null)), stateStore.countSuspended()});
        } catch (RuntimeException e) {
            gauges.set(new double[]{Double.NaN, Double.NaN});
            if (!Double.isNaN(previous[0])) {
                LOGGER.warnf("Could not refresh the oversight gauges; they read NaN until the store answers again: %s", e.getMessage());
            }
        }
    }

    /** A decision projected into analytics, with its latency when known. */
    public void decisionProjected(String surface, String pauseType, Long latencyMs) {
        if (latencyMs != null) {
            Timer.builder("eddi.hitl.decision.latency").description("How long a HITL pause waited for its decision")
                    .tags("surface", tag(surface), "pause_type", tag(pauseType)).register(registry)
                    .record(Duration.ofMillis(Math.max(0L, latencyMs)));
        }
    }

    /** A decision unit could not be stored for analytics. */
    public void projectionFailed() {
        registry.counter("eddi.hitl.decision.projection.failures").increment();
    }

    /**
     * One considered call: {@code sampled}, {@code capped}, {@code dropped} or
     * {@code skipped}.
     */
    public void sample(String outcome) {
        registry.counter("eddi.audit.sample", "outcome", outcome).increment();
    }

    /** A sample review: {@code OK} or {@code FLAGGED}. */
    public void sampleReviewed(String verdict) {
        registry.counter("eddi.audit.sample.review", "verdict", verdict).increment();
    }

    /** Samples expired unreviewed. */
    public void samplesExpired(long count) {
        if (count > 0) {
            registry.counter("eddi.audit.sample.expired").increment(count);
        }
    }

    /** A recommendation was opened: {@code PROMOTE} or {@code REVERT}. */
    public void recommendation(String kind) {
        registry.counter("eddi.autonomy.recommendation", "kind", kind).increment();
    }

    /** A recommendation was applied. */
    public void promotionApplied() {
        registry.counter("eddi.autonomy.promotion.applied").increment();
    }

    /** A category was suspended. */
    public void demotion(String trigger) {
        registry.counter("eddi.autonomy.demotion", "trigger", trigger).increment();
    }

    /** A call the overlay gated (static: the gate is not a bean). */
    public static void suspensionGated() {
        Metrics.globalRegistry.counter(SUSPENSION_GATED).increment();
    }

    private static String tag(String value) {
        return value != null ? value : "unknown";
    }
}
