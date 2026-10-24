/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget.DeliveryType;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Locale;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Micrometer meters for alerting, exposed at {@code /q/metrics}:
 * <ul>
 * <li>{@code eddi.alerts.fired} — alerts recorded, tagged {@code source};</li>
 * <li>{@code eddi.alerts.deduplicated} — alerts folded into an earlier one of
 * the same rule, agent and conversation in the same window;</li>
 * <li>{@code eddi.alerts.throttled} — alerts held back past the rule's cap (for
 * its digest, or the inbox only);</li>
 * <li>{@code eddi.alerts.deliveries} — delivery attempts, tagged {@code type}
 * (slack|webhook) and {@code outcome} (success|failure|gave_up);</li>
 * <li>{@code eddi.alerts.digests} — digests sent;</li>
 * <li>{@code eddi.alerts.dropped} — alert work dropped because the queue was
 * full, tagged {@code reason}; never expected in a healthy deployment;</li>
 * <li>{@code eddi.alerts.queue.depth} — work waiting for the alert worker (a
 * gauge registered by {@code AlertDispatcher}).</li>
 * </ul>
 * No tag carries an agent id: a rule over every agent would otherwise mint one
 * series per agent.
 */
@ApplicationScoped
public class AlertMetrics {

    static final String FIRED = "eddi.alerts.fired";
    static final String DEDUPLICATED = "eddi.alerts.deduplicated";
    static final String THROTTLED = "eddi.alerts.throttled";
    static final String DELIVERIES = "eddi.alerts.deliveries";
    static final String DIGESTS = "eddi.alerts.digests";
    static final String DROPPED = "eddi.alerts.dropped";
    static final String QUEUE_DEPTH = "eddi.alerts.queue.depth";

    private final MeterRegistry registry;

    @Inject
    public AlertMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** The queue-depth gauge of the alert worker. */
    void registerQueueDepth(ThreadPoolExecutor executor) {
        Gauge.builder(QUEUE_DEPTH, executor, e -> e.getQueue().size()).description("Alert work waiting for the alert worker").register(registry);
    }

    public void fired(AlertSource source) {
        counter(FIRED, "Alerts recorded", "source", source == null ? "digest" : source.name()).increment();
    }

    public void deduplicated() {
        counter(DEDUPLICATED, "Alerts folded into an earlier one in the same window").increment();
    }

    public void throttled() {
        counter(THROTTLED, "Alerts held back past the rule's cap").increment();
    }

    public void delivery(DeliveryType type, String outcome) {
        Counter.builder(DELIVERIES).description("Alert delivery attempts").tag("type", type.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome).register(registry).increment();
    }

    public void digest() {
        counter(DIGESTS, "Alert digests sent").increment();
    }

    public void dropped(String reason) {
        counter(DROPPED, "Alert work dropped because the alert queue was full", "reason", reason).increment();
    }

    private Counter counter(String name, String description, String... tags) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }
}
