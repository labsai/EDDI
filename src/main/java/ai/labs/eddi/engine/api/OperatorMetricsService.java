/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.api.model.OperatorCanaryReport;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backs the three write-canary and gate-verification meters the Manager cannot
 * emit itself.
 * <p>
 * Every meter here describes a fact the Manager establishes client-side — the
 * canary is a synthetic conversation it drives, the gate check is a set of
 * agent-document reads it performs — and reports over
 * {@link ai.labs.eddi.engine.rest.RestOperatorMetrics} purely so the fact
 * becomes visible on {@code /q/metrics} rather than only in a browser tab. This
 * service does not, and cannot, verify any of it independently: it trusts the
 * report the same way any metrics endpoint trusts its caller, which is exactly
 * why {@link ai.labs.eddi.engine.api.IRestOperatorMetrics} is restricted to
 * {@code eddi-admin} — the same tier that can provision the operator at all.
 */
@ApplicationScoped
public class OperatorMetricsService {

    private static final List<String> VALID_OUTCOMES = List.of(OperatorCanaryReport.OUTCOME_PASS, OperatorCanaryReport.OUTCOME_FAIL,
            OperatorCanaryReport.OUTCOME_UNKNOWN);

    private final MeterRegistry meterRegistry;

    /**
     * Backing store for {@code eddi.operator.gate.verified}. 1 while every
     * provisioned version last read back with a sound gate, 0 otherwise — including
     * before any report has ever arrived. A fresh deployment that has never
     * activated an operator therefore also reads 0: "not yet proven true" is the
     * correct default for anything this metric guards, even though it cannot be
     * distinguished from "activated, and broken" by this signal alone.
     */
    private final AtomicInteger gateVerified = new AtomicInteger(0);

    @Inject
    public OperatorMetricsService(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Public rather than package-private: under CDI this runs automatically via
     * {@code @PostConstruct}, but a test constructing this service directly (no
     * container) has to be able to call it too, or the gauge is never registered
     * and every read-back is silently absent.
     */
    @PostConstruct
    public void registerGateGauge() {
        // Registered once, here, rather than on every report: a Micrometer gauge is
        // a live read of a supplier, not a value you push — calling gauge(...)
        // again on each report would keep re-registering the same meter id, which
        // most registries tolerate but is not the contract.
        meterRegistry.gauge("eddi.operator.gate.verified", gateVerified, AtomicInteger::get);
    }

    /**
     * Whether a canary/gate report's outcome string is one this service accepts.
     * {@code List.of(...).contains(null)} throws NPE rather than returning false,
     * so null is checked explicitly ahead of it.
     */
    public static boolean isValidOutcome(String outcome) {
        return outcome != null && VALID_OUTCOMES.contains(outcome);
    }

    /**
     * @param outcome
     *            must be one of {@link #isValidOutcome} — validated by the REST
     *            layer before this is called, so an invalid value here is a
     *            programming error, not a client mistake to degrade gracefully for.
     */
    public void recordCanaryResult(String outcome, Long durationMs) {
        Counter.builder("eddi.operator.canary").tag("outcome", outcome).register(meterRegistry).increment();
        if (durationMs != null && durationMs >= 0) {
            Timer.builder("eddi.operator.canary.duration").register(meterRegistry).record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordGateStatus(boolean verified) {
        gateVerified.set(verified ? 1 : 0);
    }
}
