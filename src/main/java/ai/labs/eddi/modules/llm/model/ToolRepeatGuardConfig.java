/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent-designer configuration for the tool repeat guard: what the tool loop
 * does when the model re-sends a tool call that already failed.
 * <p>
 * Every field is optional and the whole object may be absent. An absent
 * {@code toolRepeatGuard} means <strong>enabled with the defaults
 * below</strong>; {@code "enabled": false} restores the behaviour from before
 * the guard existed, exactly.
 * <p>
 * The guard only ever refuses a call that failed identically (same tool, same
 * arguments up to key order) and where nothing has succeeded since, so it costs
 * a legitimately working agent nothing. See
 * {@code docs/langchain.md#repeated-failing-tool-calls}.
 *
 * @since 6.5.0
 */
public class ToolRepeatGuardConfig {

    /** Default for {@link #maxIdenticalFailures}. */
    public static final int DEFAULT_MAX_IDENTICAL_FAILURES = 2;

    /** Default for {@link #maxRefusalsPerTurn}. */
    public static final int DEFAULT_MAX_REFUSALS_PER_TURN = 3;

    /**
     * Master switch. Null means enabled; {@code false} disables the guard entirely.
     */
    private Boolean enabled;

    /**
     * How many times an identical call may fail with a <em>retryable</em> failure
     * (timeout, rate limit, HTTP 408/425/429/5xx, unclassified error) before the
     * next identical call is refused. Default 2, so the third identical attempt is
     * refused. A <em>terminal</em> failure (other 4xx, rejected arguments, unknown
     * tool) is not retried at all: the very next identical call is refused. In both
     * cases a success by any other call in between lifts the refusal, because the
     * model may have changed the precondition.
     */
    private Integer maxIdenticalFailures;

    /**
     * Opt-in ceiling on identical <em>successful</em> calls per turn. Repeating a
     * successful call is normal (polling a job status), so the default is no limit;
     * a positive value refuses the call once it has succeeded that many times.
     * {@code -1} (or {@code 0}, or unset) means no limit.
     */
    private Integer maxIdenticalCalls;

    /**
     * Refusals tolerated in one turn before the loop stops asking the model for
     * more and ends the turn with a deterministic message (default 3). {@code -1}
     * (or {@code 0}) means the loop never escalates and only refuses.
     */
    private Integer maxRefusalsPerTurn;

    /**
     * Tools the guard never tracks or refuses, by dispatch name or by built-in
     * whitelist slug (resolved like {@code toolTimeoutsMs}). For tools whose
     * identical failing call is legitimately retried, e.g. a status poll that
     * answers 404 until the job exists.
     */
    private List<String> exemptTools;

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getMaxIdenticalFailures() {
        return maxIdenticalFailures;
    }

    public void setMaxIdenticalFailures(Integer maxIdenticalFailures) {
        this.maxIdenticalFailures = maxIdenticalFailures;
    }

    public Integer getMaxIdenticalCalls() {
        return maxIdenticalCalls;
    }

    public void setMaxIdenticalCalls(Integer maxIdenticalCalls) {
        this.maxIdenticalCalls = maxIdenticalCalls;
    }

    public Integer getMaxRefusalsPerTurn() {
        return maxRefusalsPerTurn;
    }

    public void setMaxRefusalsPerTurn(Integer maxRefusalsPerTurn) {
        this.maxRefusalsPerTurn = maxRefusalsPerTurn;
    }

    public List<String> getExemptTools() {
        return exemptTools;
    }

    public void setExemptTools(List<String> exemptTools) {
        this.exemptTools = exemptTools != null ? new ArrayList<>(exemptTools) : null;
    }
}
