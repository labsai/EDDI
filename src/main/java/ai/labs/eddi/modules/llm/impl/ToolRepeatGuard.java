/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.crypto.JacksonCanonicalizer;
import ai.labs.eddi.modules.llm.impl.ToolOutcomeClassifier.Outcome;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.ToolRepeatGuardConfig;
import ai.labs.eddi.modules.llm.tools.ToolNameResolver;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stops the tool loop from re-sending a call that already failed.
 * <p>
 * Inside the iteration cap a model can still send the same failing call (a 403,
 * a 404, a schema-rejected body) in every round and in every parallel slot,
 * burning cost and rate-limit budget and, for a non-idempotent endpoint,
 * repeating side effects. This guard refuses such a call <em>without executing
 * it</em>.
 *
 * <h3>The rule</h3> A call is identified by its tool dispatch name plus its
 * arguments canonicalised for key order. It is refused when its last outcome
 * was a failure <strong>and no other call has succeeded since</strong>:
 * <ul>
 * <li>a terminal failure is refused on the next identical call;</li>
 * <li>a retryable failure is allowed until {@code maxIdenticalFailures}
 * consecutive failures have been seen;</li>
 * <li>an identical success is never refused (polling is legitimate) unless
 * {@code maxIdenticalCalls} opts in.</li>
 * </ul>
 * "Some other call succeeded" lifts a refusal because the model may have fixed
 * the precondition: a 404 on {@code getUser}, then a successful
 * {@code createUser}, makes the identical {@code getUser} worth trying again.
 * <p>
 * A call that never ran ({@link Outcome#NOT_EXECUTED}) is not recorded at all,
 * so a refusal can never count as a failure and breed more refusals.
 *
 * <h3>State</h3> One instance per run of the tool loop, created on the call
 * stack of {@code ToolLoopRunner#runToolCallLoop}. The runner is a shared
 * singleton and must stay stateless, so this is never a field. A HITL resume
 * starts a fresh loop run and therefore a fresh guard. Not thread-safe: the
 * loop executes a round's calls in order on one thread.
 */
final class ToolRepeatGuard {

    /**
     * Why a call was refused, for the model-facing text, the trace and the metric.
     */
    record Refusal(String reasonCode, Outcome lastOutcome, String text) {
    }

    /** Reason tag for a refusal of a call that already failed terminally. */
    static final String REASON_TERMINAL = "terminal_failure";
    /** Reason tag for a refusal of a call that failed retryably too often. */
    static final String REASON_RETRYABLE = "retryable_failure";
    /** Reason tag for a refusal by the opt-in identical-success ceiling. */
    static final String REASON_IDENTICAL_CALLS = "identical_success_limit";

    private static final class Entry {
        Outcome lastOutcome = Outcome.SUCCESS;
        int consecutiveFailures;
        long successMark;
        int successes;
        String failureDetail = "an error";
    }

    private final int maxIdenticalFailures;
    private final int maxIdenticalCalls;
    private final int maxRefusals;
    private final Set<String> exempt;
    private final Map<String, String> canonicalNames;

    private final Map<String, Entry> entries = new HashMap<>();
    private long successCounter;
    private int refusals;

    private ToolRepeatGuard(ToolRepeatGuardConfig config, Map<String, String> canonicalNames) {
        this.maxIdenticalFailures = config.getMaxIdenticalFailures() == null
                ? ToolRepeatGuardConfig.DEFAULT_MAX_IDENTICAL_FAILURES
                : Math.max(1, config.getMaxIdenticalFailures());
        this.maxIdenticalCalls = config.getMaxIdenticalCalls() == null || config.getMaxIdenticalCalls() <= 0
                ? Integer.MAX_VALUE
                : config.getMaxIdenticalCalls();
        this.maxRefusals = config.getMaxRefusalsPerTurn() == null
                ? ToolRepeatGuardConfig.DEFAULT_MAX_REFUSALS_PER_TURN
                : (config.getMaxRefusalsPerTurn() <= 0 ? Integer.MAX_VALUE : config.getMaxRefusalsPerTurn());
        List<String> exemptTools = config.getExemptTools();
        this.exempt = exemptTools == null ? Set.of() : new HashSet<>(exemptTools);
        this.canonicalNames = canonicalNames;
    }

    /**
     * The guard for one loop run, or {@code null} when the task turned it off. An
     * absent {@code toolRepeatGuard} is "enabled with defaults".
     */
    static ToolRepeatGuard forTask(LlmConfiguration.Task task, Map<String, String> canonicalNames) {
        ToolRepeatGuardConfig config = task.getToolRepeatGuard();
        if (config == null) {
            config = new ToolRepeatGuardConfig();
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            return null;
        }
        return new ToolRepeatGuard(config, canonicalNames);
    }

    /**
     * Whether this call may run. Returns {@code null} to allow it, or the refusal.
     * A refusal is counted here.
     */
    Refusal check(String toolName, String arguments) {
        if (isExempt(toolName)) {
            return null;
        }
        Entry entry = entries.get(fingerprint(toolName, arguments));
        if (entry == null) {
            return null;
        }
        Refusal refusal = null;
        if (entry.lastOutcome.isFailure() && successCounter == entry.successMark) {
            boolean terminal = entry.lastOutcome == Outcome.FAILED_TERMINAL;
            if (terminal || entry.consecutiveFailures >= maxIdenticalFailures) {
                String times = entry.consecutiveFailures > 1 ? " " + entry.consecutiveFailures + " times" : "";
                refusal = new Refusal(terminal ? REASON_TERMINAL : REASON_RETRYABLE, entry.lastOutcome,
                        "identical call already failed" + times + " (" + entry.failureDetail + ") and nothing has changed since; "
                                + "do not repeat it. Report the failure to the user or try a different approach");
            }
        } else if (entry.successes >= maxIdenticalCalls) {
            refusal = new Refusal(REASON_IDENTICAL_CALLS, Outcome.SUCCESS,
                    "identical call already succeeded " + entry.successes + " times this turn (maxIdenticalCalls); "
                            + "do not repeat it. Use the earlier result");
        }
        if (refusal != null) {
            refusals++;
        }
        return refusal;
    }

    /**
     * Records the outcome of a call that ran. {@code raw} is the tool's result
     * before any provenance envelope. A call that did not run is not recorded.
     */
    void record(String toolName, String arguments, Outcome outcome, String raw) {
        if (outcome == Outcome.NOT_EXECUTED || isExempt(toolName)) {
            return;
        }
        Entry entry = entries.computeIfAbsent(fingerprint(toolName, arguments), k -> new Entry());
        if (outcome.isFailure()) {
            // A failure straight after progress starts a new run of failures: only
            // failures with nothing succeeding in between are "consecutive".
            entry.consecutiveFailures = entry.lastOutcome.isFailure() && successCounter == entry.successMark
                    ? entry.consecutiveFailures + 1
                    : 1;
            entry.lastOutcome = outcome;
            entry.successMark = successCounter;
            entry.failureDetail = ToolOutcomeClassifier.describeFailure(raw);
        } else {
            successCounter++;
            entry.lastOutcome = Outcome.SUCCESS;
            entry.consecutiveFailures = 0;
            entry.successes++;
        }
    }

    /**
     * Whether enough refusals have accumulated that the loop should stop asking the
     * model for more.
     */
    boolean shouldEscalate() {
        return refusals >= maxRefusals;
    }

    int refusals() {
        return refusals;
    }

    /** The {@code NOT_EXECUTED} result a refused call receives. */
    static String refusalEnvelope(Refusal refusal) {
        // The text is built from fixed phrases and a status code, never from tool
        // output, so it needs no JSON escaping beyond what is written here.
        return "{\"status\":\"NOT_EXECUTED\",\"reason\":\"" + refusal.text().replace("\"", "'") + "\"}";
    }

    private boolean isExempt(String toolName) {
        if (exempt.isEmpty()) {
            return false;
        }
        return exempt.contains(toolName) || exempt.contains(ToolNameResolver.canonical(toolName, canonicalNames));
    }

    /**
     * Tool name plus arguments with object keys sorted recursively, so
     * {@code {"a":1,"b":2}} and {@code {"b":2,"a":1}} are one call. Arguments that
     * are not JSON fall back to the trimmed raw string.
     */
    static String fingerprint(String toolName, String arguments) {
        String args = arguments == null ? "" : arguments.strip();
        try {
            if (!args.isEmpty()) {
                args = JacksonCanonicalizer.canonicalize(args);
            }
        } catch (Exception notCanonicalisable) {
            // not JSON, or JSON with duplicate keys: the raw string identifies it
        }
        return toolName + '\u0000' + args;
    }

    /**
     * The turn's answer when the guard has stopped the loop. Like
     * {@link ToolLoopRunner#iterationBudgetSpentMessage}: what stopped, what state
     * the work is in, how to go on.
     */
    static String stoppedMessage(int refusals) {
        return "I stopped because I kept repeating tool calls that had already failed (" + refusals
                + " repeated attempts were blocked without running). Calls that completed earlier have taken effect and "
                + "are not rolled back; the blocked ones were not run. Tell me how to proceed, for example a different "
                + "approach or corrected details, and I will continue. An agent designer can tune this with the "
                + "task's toolRepeatGuard setting.";
    }
}
