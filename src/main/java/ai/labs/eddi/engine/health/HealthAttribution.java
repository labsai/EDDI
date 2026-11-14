/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;

import java.util.Set;

/**
 * Who is accountable for a failed turn — the agent, or the platform it runs on.
 * The reliability section keeps the two apart so that a provider outage does
 * not turn every agent red (plan §4.3).
 * <p>
 * <b>Platform</b> failure classes are the ones a provider or the network causes
 * and that repeating can cure: {@code TRANSIENT}, {@code RATE_LIMITED} and
 * {@code TIMEOUT} — exactly the classes {@link FailureClass#isRetryable()}
 * marks. Every other class ({@code QUOTA_EXHAUSTED}, {@code AUTH},
 * {@code BAD_REQUEST}, {@code CONTEXT_TOO_LONG}, {@code MODEL_NOT_FOUND},
 * {@code UNKNOWN}) is the agent's: its key, its prompt size, its model id.
 * <p>
 * A failed turn ({@code errorKind} set) is attributed:
 * <ul>
 * <li>{@code TURN_INTERRUPTED} (a lost cluster lease, a cancel, a stop) and
 * {@code TENANT_BUDGET} (the deployment's budget) — <b>platform</b>: nothing in
 * the agent's configuration caused them;</li>
 * <li>{@code COST_CEILING} — <b>agent</b>: its own
 * {@code maxCostPerConversationUsd};</li>
 * <li>{@code TURN_ERROR} and {@code TURN_DEADLINE} — <b>platform</b> when the
 * failed provider attempts nobody recovered from are all of a platform class
 * (the provider was down, slow or throttling, and that ended the turn),
 * otherwise <b>agent</b> — a pipeline error, a tool loop past the agent's own
 * timeout, a bad request.</li>
 * </ul>
 * A turn is <em>affected</em> by the platform when it failed for a platform
 * reason, or when any of its provider attempts failed with a platform class —
 * recovered or not. That is the informational platform failure rate: the
 * provider trouble users mostly never saw.
 */
public final class HealthAttribution {

    /** Who a failed turn is attributed to. */
    public enum Party {
        NONE, AGENT, PLATFORM
    }

    /** Failure classes a provider or the network causes. */
    public static final Set<String> PLATFORM_CLASSES = Set.of(FailureClass.TRANSIENT.name(), FailureClass.RATE_LIMITED.name(),
            FailureClass.TIMEOUT.name());

    private HealthAttribution() {
    }

    /** Whether a failure class is the platform's. */
    public static boolean isPlatformClass(String failureClass) {
        return failureClass != null && PLATFORM_CLASSES.contains(failureClass);
    }

    /**
     * Who a turn's failure is attributed to; {@link Party#NONE} when it did not
     * fail.
     */
    public static Party attribute(HealthTurn turn) {
        if (turn == null || turn.errorKind() == null) {
            return Party.NONE;
        }
        String kind = turn.errorKind();
        if (ErrorKind.TURN_INTERRUPTED.name().equals(kind) || ErrorKind.TENANT_BUDGET.name().equals(kind)) {
            return Party.PLATFORM;
        }
        if (ErrorKind.COST_CEILING.name().equals(kind)) {
            return Party.AGENT;
        }
        boolean unrecovered = false;
        for (HealthFailure failure : turn.failures()) {
            if (failure.recovered()) {
                continue;
            }
            unrecovered = true;
            if (!isPlatformClass(failure.failureClass())) {
                return Party.AGENT;
            }
        }
        return unrecovered ? Party.PLATFORM : Party.AGENT;
    }

    /**
     * Whether a platform event touched the turn: a platform-caused failure, or a
     * failed provider attempt of a platform class, recovered or not.
     */
    public static boolean platformAffected(HealthTurn turn) {
        if (turn == null) {
            return false;
        }
        if (attribute(turn) == Party.PLATFORM) {
            return true;
        }
        for (HealthFailure failure : turn.failures()) {
            if (isPlatformClass(failure.failureClass())) {
                return true;
            }
        }
        return false;
    }
}
