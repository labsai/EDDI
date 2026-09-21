/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api.model;

/**
 * Outcome of one client-run write canary, reported for {@code /q/metrics}
 * visibility.
 * <p>
 * The canary itself runs in the Manager: it starts a synthetic conversation,
 * provokes a real gated write, asserts the turn paused with the expected tool
 * pending, then rejects it so nothing executes. The backend has no way to
 * observe that sequence on its own — a conversation looks like any other from
 * this side — so the Manager reports the result after the fact.
 *
 * @param outcome
 *            {@code pass}, {@code fail}, or {@code unknown}. Fixed vocabulary,
 *            validated server-side — never free text, so the metric's
 *            cardinality cannot grow from client input.
 * @param durationMs
 *            wall-clock time of the probe; negative or absent values are simply
 *            not recorded as a timer sample rather than rejected, since a
 *            malformed duration says nothing about whether the gate held.
 */
public record OperatorCanaryReport(String outcome, Long durationMs) {

    public static final String OUTCOME_PASS = "pass";
    public static final String OUTCOME_FAIL = "fail";
    public static final String OUTCOME_UNKNOWN = "unknown";
}
