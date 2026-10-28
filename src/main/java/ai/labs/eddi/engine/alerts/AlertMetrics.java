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
 * <li>{@code eddi.alerts.digests} — digests created;</li>
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

    /** The outcomes {@code delivery} is ever called with. */
    private static final String[] DELIVERY_OUTCOMES = {"success", "failure", "gave_up"};

    private final MeterRegistry registry;

    @Inject
    public AlertMetrics(MeterRegistry registry) {
        this.registry = registry;
        preRegister();
    }

    /**
     * Registers the bounded delivery and dropped series at zero, so the first
     * failure after a restart is a rise from 0 that {@code rate()} and
     * {@code increase()} can see, and a dashboard panel shows 0 instead of no data.
     */
    private void preRegister() {
        for (DeliveryType type : DeliveryType.values()) {
            for (String outcome : DELIVERY_OUTCOMES) {
                deliveryCounter(type, outcome);
            }
        }
        dropped0(AlertDispatcher.REASON_QUEUE_FULL);
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
        deliveryCounter(type, outcome).increment();
    }

    private Counter deliveryCounter(DeliveryType type, String outcome) {
        return Counter.builder(DELIVERIES).description("Alert delivery attempts").tag("type", type.name().toLowerCase(Locale.ROOT))
                .tag("outcome", outcome).register(registry);
    }

    public void digest() {
        counter(DIGESTS, "Alert digests created").increment();
    }

    public void dropped(String reason) {
        dropped0(reason).increment();
    }

    private Counter dropped0(String reason) {
        return counter(DROPPED, "Alert work dropped because the alert queue was full", "reason", reason);
    }

    private Counter counter(String name, String description, String... tags) {
        return Counter.builder(name).description(description).tags(tags).register(registry);
    }
}
