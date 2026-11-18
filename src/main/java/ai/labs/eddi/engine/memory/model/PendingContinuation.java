/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import java.util.Objects;

/**
 * The outbox record of a self-continuation: the conversation's latest committed
 * step decided to continue, and this is everything needed to arm that
 * continuation's schedule. It is written in the same document write as the
 * step, so a turn that committed a {@code continuation:next} always leaves it
 * behind, whatever happens to the process afterwards.
 * <p>
 * It lives at the top level of the stored conversation, not in the step, so the
 * recovery sweep ({@code ContinuationRecoverySweep}) can find the conversations
 * that carry one with an index instead of reading their histories. A sweep that
 * finds its schedule marks it handled by removing {@link #getDecidedAt()} with
 * a narrow field update, which takes it out of that index; the record itself is
 * removed only when the conversation is ended or cancelled, or by the next
 * committed turn, which derives the field from its own step.
 * <p>
 * A plain bean rather than a record: the MongoDB codec and the JSON mapper both
 * need a no-argument constructor and setters.
 */
public class PendingContinuation {

    private Integer number;
    private Integer maxContinuations;
    private String input;
    private String environment;
    /** Epoch milliseconds — a number in both stores, so it can be range-queried. */
    private Long decidedAt;
    private String decisionId;

    public PendingContinuation() {
    }

    public PendingContinuation(Integer number, Integer maxContinuations, String input, String environment, Long decidedAt) {
        this(number, maxContinuations, input, environment, decidedAt, null);
    }

    public PendingContinuation(Integer number, Integer maxContinuations, String input, String environment, Long decidedAt,
            String decisionId) {
        this.number = number;
        this.maxContinuations = maxContinuations;
        this.input = input;
        this.environment = environment;
        this.decidedAt = decidedAt;
        this.decisionId = decisionId;
    }

    /** The number of the continuation to run. */
    public Integer getNumber() {
        return number;
    }

    public void setNumber(Integer number) {
        this.number = number;
    }

    /** The agent's {@code maxContinuations} when the turn decided. */
    public Integer getMaxContinuations() {
        return maxContinuations;
    }

    public void setMaxContinuations(Integer maxContinuations) {
        this.maxContinuations = maxContinuations;
    }

    /** The agent's literal {@code continuationInput} when the turn decided. */
    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    /** The deployment environment the turn ran in. */
    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    /**
     * When the turn decided, in epoch milliseconds; {@code null} once a sweep has
     * confirmed the schedule exists (the record is then out of the sweep's index).
     */
    public Long getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Long decidedAt) {
        this.decidedAt = decidedAt;
    }

    /**
     * The id of the decision, minted by the turn that decided and also stored in
     * its step. Unlike {@link #getDecidedAt()} it stays when a sweep marks the
     * record handled, so marks, removals and schedule ids all match this one
     * decision and never a later decision of the same number.
     */
    public String getDecisionId() {
        return decisionId;
    }

    public void setDecisionId(String decisionId) {
        this.decisionId = decisionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PendingContinuation that)) {
            return false;
        }
        return Objects.equals(number, that.number) && Objects.equals(maxContinuations, that.maxContinuations)
                && Objects.equals(input, that.input) && Objects.equals(environment, that.environment)
                && Objects.equals(decidedAt, that.decidedAt) && Objects.equals(decisionId, that.decisionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(number, maxContinuations, input, environment, decidedAt, decisionId);
    }

    @Override
    public String toString() {
        return "PendingContinuation{number=" + number + ", max=" + maxContinuations + ", decidedAt=" + decidedAt + ", decisionId=" + decisionId
                + "}";
    }
}
