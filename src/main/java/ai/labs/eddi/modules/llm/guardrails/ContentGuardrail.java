/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A guardrail that screens text: the user's message (input) or the model's
 * answer (output). Implementations are {@code @ApplicationScoped} CDI beans —
 * adding a guardrail type means adding a bean and nothing else: the
 * {@link GuardrailService} discovers every bean of this type and selects it by
 * {@link #type()}.
 * <h3>Contract</h3>
 * <ul>
 * <li>Stateless: all per-call context arrives in {@link GuardrailContext}.
 * Caching data derived from configuration (compiled patterns) is fine.</li>
 * <li>Report, do not act: return a {@link GuardrailVerdict}; the configured
 * action decides what happens.</li>
 * <li>Never copy the screened text into the verdict's {@code reason}.</li>
 * <li>Do not throw, except {@link GuardrailUndecidedException} to report "could
 * not decide" (a backing service unreachable or answering nonsense) with an
 * audit reason and the cost already incurred. Any {@link RuntimeException} is
 * caught and logged, and the guardrail's {@code failMode} decides — block or
 * warn — so a broken guardrail neither takes a conversation down nor puts its
 * message on a path that classifies exceptions for retry.</li>
 * </ul>
 */
public interface ContentGuardrail {

    /** The {@code type} value a task config names this guardrail by. */
    String type();

    /** The phases this guardrail can run in. */
    Set<GuardrailPhase> phases();

    /** Screens {@code text} (never null) and reports what it found. */
    GuardrailVerdict evaluate(String text, GuardrailContext context);

    /**
     * Whether the input phase also runs this guardrail over the earlier user
     * messages of the history window. Conversation memory keeps every message as
     * the user sent it, so without this a message blocked or redacted on its own
     * turn would reach the model unscreened on the next one. Cheap, deterministic
     * guardrails return true (the default); a guardrail that calls a model should
     * return false rather than multiply its cost by the window size.
     */
    default boolean screensHistory() {
        return true;
    }

    /**
     * Checks this guardrail's {@code parameters} when the task is deployed: a
     * message naming the problem, or null when the configuration is usable. A
     * configuration that cannot work at all (rather than one that is merely
     * unusual) is worth refusing up front instead of failing every turn.
     */
    default String validate(Map<String, String> parameters) {
        return null;
    }

    /**
     * Configuration that works but deserves a word at deploy time (logged as a
     * WARN, never refused) — e.g. a model-backed guardrail without prices, whose
     * calls the turn's cost ceilings cannot see.
     */
    default List<String> warnings(Map<String, String> parameters) {
        return List.of();
    }
}
