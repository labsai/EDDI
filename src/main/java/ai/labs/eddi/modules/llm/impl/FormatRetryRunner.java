/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.FailureClass;
import ai.labs.eddi.configs.shared.LlmFailureClassifier;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.impl.builder.AnthropicLanguageModelBuilder;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ResponseValidation;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The same-model recovery step of R5: when a model's reply is unusable, ask
 * <em>that model</em> again before anyone pays for a different one.
 * <p>
 * Stateless and shared by every conversation. It owns no model call itself: the
 * caller hands in an {@link Asker} (plain chat, or the final model call of a
 * tool loop) and the runner decides whether, how and how often to use it.
 *
 * <h2>What triggers a re-ask</h2> Only the {@code responseValidation} policies
 * set to {@code "retry"} ({@link Trigger}). In detection order:
 * <ol>
 * <li>{@code EMPTY} — blank reply;</li>
 * <li>{@code TRUNCATION} — {@code finishReason=LENGTH}: re-asked <b>once</b>
 * with the output-token cap multiplied by {@code truncationRetryFactor};</li>
 * <li>{@code CONTENT_FILTER} — one more sample of the same request;</li>
 * <li>{@code INVALID_JSON} — a {@code convertToObject} reply that
 * {@link ModelOutputParser} (which already tried fence stripping and extraction
 * — the free local repair) still rejects;</li>
 * <li>{@code CONTEXT_TOO_LONG} — the provider refused the prompt: <b>once</b>,
 * with the history window halved ({@link ContextShrinker}).</li>
 * </ol>
 * {@code SCHEMA_MISMATCH} — a reply that parses but breaks the response shape
 * ({@code responseSchema}, {@code nonBlankFields}); re-asked like invalid JSON,
 * with the violation as the corrective message's reason.
 *
 * <h2>The corrective message</h2> Only an invalid-JSON re-ask carries one. The
 * request is the original messages, then the model's own bad reply as an
 * <em>assistant</em> turn (capped at {@link #MAX_ECHOED_REPLY_CHARS}), then one
 * user message: the configured text with {@code {reason}} replaced literally by
 * {@link ModelOutputParser}'s own constant reason. The user-role message
 * therefore never contains model output or user input — the model sees its own
 * words in its own role, the standard corrective-prompting shape, and the only
 * dynamic part of the user turn is a string EDDI wrote. The text is
 * deliberately not run through the template engine. Empty, truncated and
 * filtered replies are re-asked with the original request unchanged: there is
 * nothing for a corrective sentence to tell the model that the same request
 * would not.
 *
 * <h2>Budget</h2> {@code maxRetries} is per model (cascade step) and shared by
 * every policy; a re-ask is skipped when the remaining time cannot fit it
 * ({@link RemainingBudget}, {@code minAttemptMs}), when the retry cost cap is
 * reached, or when {@link ReaskGate} says the model is known-bad (the R8 hook).
 * Every skipped or failed re-ask leaves the reply as it was: the caller
 * escalates or falls back; the runner never throws for a format problem. Token
 * usage of every attempt is summed into the result, so retries show up in the
 * cost figures.
 */
final class FormatRetryRunner {

    private static final Logger LOGGER = Logger.getLogger(FormatRetryRunner.class);

    /**
     * Longest bad reply echoed back as the assistant turn of a corrective re-ask.
     */
    static final int MAX_ECHOED_REPLY_CHARS = 16_000;
    /** The output-token cap of a truncation re-ask never exceeds this. */
    static final int MAX_OUTPUT_TOKENS_CEILING = 32_768;

    /** What made a reply unusable. */
    enum Trigger {
        EMPTY("empty"), TRUNCATION("truncated"), CONTENT_FILTER("content_filter"), INVALID_JSON("invalid_json"), SCHEMA_MISMATCH(
                "schema_mismatch"), CONTEXT_TOO_LONG("context_too_long");

        private final String label;

        Trigger(String label) {
            this.label = label;
        }

        /** The metric / trace / SSE value. */
        String label() {
            return label;
        }
    }

    /**
     * How much time the model being asked still has. The step's own timeout feeds
     * it today; the turn deadline (R1) combines in with {@link #min}.
     */
    @FunctionalInterface
    interface RemainingBudget {
        /** No bound — the default until a caller supplies one. */
        RemainingBudget UNBOUNDED = () -> Long.MAX_VALUE;

        long remainingMs();

        /** A budget that runs out at {@code deadlineEpochMs}. */
        static RemainingBudget until(long deadlineEpochMs) {
            return () -> deadlineEpochMs - System.currentTimeMillis();
        }

        /** The tighter of two budgets. */
        default RemainingBudget min(RemainingBudget other) {
            return () -> Math.min(remainingMs(), other.remainingMs());
        }
    }

    /**
     * Hook for the circuit breaker (R8): returns false to skip the same-model
     * re-ask because this model has been producing the same kind of unusable reply
     * for most recent turns, so repeating it only burns time.
     */
    @FunctionalInterface
    interface ReaskGate {
        ReaskGate ALWAYS = trigger -> true;

        boolean allowReask(Trigger trigger);
    }

    /** Rebuilds the request with a smaller history window; null when it cannot. */
    @FunctionalInterface
    interface ContextShrinker {
        List<ChatMessage> shrink();
    }

    /** Called before each re-ask (SSE {@code llm_retry}, trace). */
    @FunctionalInterface
    interface ReaskListener {
        ReaskListener NONE = (trigger, attempt) -> {
        };

        void onReask(Trigger trigger, int attempt);
    }

    /**
     * One model call: the reply text and its metadata ({@code warning}, tokens).
     */
    record Attempt(String text, Map<String, Object> metadata) {
    }

    /**
     * One model call over the given messages, optionally with a raised output cap.
     */
    @FunctionalInterface
    interface Asker {
        Attempt ask(List<ChatMessage> messages, Integer maxOutputTokens) throws LifecycleException;
    }

    /**
     * What the runner hands back.
     *
     * @param attempt
     *            the last reply obtained; its metadata's {@code tokenUsage} is the
     *            sum over every attempt
     * @param unresolved
     *            the trigger still unresolved when the runner gave up, or null when
     *            the reply is usable
     * @param reason
     *            EDDI's reason for {@code unresolved}; never model output
     * @param reasks
     *            how many re-asks were sent
     * @param messages
     *            the message list the last attempt was asked with (context retries
     *            change it)
     */
    record Outcome(Attempt attempt, Trigger unresolved, String reason, int reasks, List<ChatMessage> messages) {
        boolean usable() {
            return unresolved == null;
        }
    }

    /**
     * The policy for one model: which triggers re-ask, and the limits.
     *
     * @param baseMaxOutputTokens
     *            the output-token cap the model runs with, or null when unknown (a
     *            truncation re-ask then cannot raise it and is skipped)
     */
    record Policy(Set<Trigger> retryOn, int maxRetries, double truncationFactor, String correctiveMessage, long minAttemptMs,
            Double maxRetryCostUsd, Double inputPricePer1M, Double outputPricePer1M, boolean convertToObject, Integer baseMaxOutputTokens,
            String responseSchema, List<String> nonBlankFields, String taskId, String emptyAction) {

        /**
         * The policy a task (and optionally a cascade step) runs under, or null when
         * validation is off or no policy is {@code "retry"}.
         *
         * @param stepMaxFormatRetries
         *            the cascade step's {@code maxFormatRetries}, or null
         */
        static Policy from(ResponseValidation validation, Integer stepMaxFormatRetries, boolean convertToObject, Integer baseMaxOutputTokens,
                           Double inputPricePer1M, Double outputPricePer1M) {
            return from(validation, stepMaxFormatRetries, convertToObject, baseMaxOutputTokens, inputPricePer1M, outputPricePer1M, null, null, null);
        }

        /**
         * As above, with the task's response shape ({@code responseSchema},
         * {@code nonBlankFields}) so a reply that parses but breaks it is detected (the
         * {@code onSchemaMismatch} policy).
         */
        static Policy from(ResponseValidation validation, Integer stepMaxFormatRetries, boolean convertToObject, Integer baseMaxOutputTokens,
                           Double inputPricePer1M, Double outputPricePer1M, String responseSchema, List<String> nonBlankFields, String taskId) {
            if (validation == null || !validation.isEnabled()) {
                return null;
            }
            Set<Trigger> on = EnumSet.noneOf(Trigger.class);
            addIfRetry(on, Trigger.EMPTY, validation.getOnEmpty());
            addIfRetry(on, Trigger.TRUNCATION, validation.getOnTruncation());
            addIfRetry(on, Trigger.CONTENT_FILTER, validation.getOnContentFilter());
            addIfRetry(on, Trigger.CONTEXT_TOO_LONG, validation.getOnContextTooLong());
            if (convertToObject) {
                addIfRetry(on, Trigger.INVALID_JSON, validation.getOnInvalidJson());
                addIfRetry(on, Trigger.SCHEMA_MISMATCH, validation.getOnSchemaMismatch());
            }
            if (on.isEmpty()) {
                return null;
            }
            int retries = stepMaxFormatRetries != null ? stepMaxFormatRetries : validation.getMaxRetries();
            retries = Math.max(0, Math.min(ResponseValidation.MAX_RETRIES_CEILING, retries));
            String corrective = validation.getCorrectiveMessage();
            if (corrective == null || corrective.isBlank()) {
                corrective = convertToObject ? ResponseValidation.DEFAULT_CORRECTIVE_MESSAGE : ResponseValidation.DEFAULT_CORRECTIVE_MESSAGE_TEXT;
            }
            return new Policy(Set.copyOf(on), retries, validation.getTruncationRetryFactor(), corrective, validation.getMinAttemptMs(),
                    validation.getMaxRetryCostUsd(), inputPricePer1M, outputPricePer1M, convertToObject, baseMaxOutputTokens, responseSchema,
                    nonBlankFields, taskId, validation.getOnEmpty());
        }

        private static void addIfRetry(Set<Trigger> on, Trigger trigger, String action) {
            if ("retry".equalsIgnoreCase(action)) {
                on.add(trigger);
            }
        }

        /**
         * Whether a blank reply to a JSON task may be re-asked as invalid JSON: not
         * when {@code onEmpty} is {@code fallback} or {@code error}, which
         * {@code LlmTask} must get to apply to the blank reply itself.
         */
        boolean blankMayBeReaskedAsInvalidJson() {
            return !"fallback".equalsIgnoreCase(emptyAction) && !"error".equalsIgnoreCase(emptyAction);
        }

        boolean retries(Trigger trigger) {
            return retryOn.contains(trigger);
        }
    }

    private final ModelOutputParser parser;
    private final MeterRegistry meterRegistry;

    FormatRetryRunner(ModelOutputParser parser, MeterRegistry meterRegistry) {
        this.parser = parser;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Asks the model and recovers: the first attempt happens here so a
     * {@code CONTEXT_TOO_LONG} refusal can be answered with a smaller window.
     * Anything but a context refusal propagates exactly as before.
     */
    Outcome run(Policy policy, List<ChatMessage> messages, Asker asker, ContextShrinker shrinker, RemainingBudget budget, ReaskGate gate,
                ReaskListener listener)
            throws LifecycleException {
        List<ChatMessage> current = messages;
        int reasks = 0;
        Attempt first;
        try {
            first = asker.ask(current, null);
        } catch (LifecycleException e) {
            if (!policy.retries(Trigger.CONTEXT_TOO_LONG) || shrinker == null || e instanceof LifecycleException.LifecycleInterruptedException
                    || LlmFailureClassifier.classify(e).cls() != FailureClass.CONTEXT_TOO_LONG || policy.maxRetries() < 1
                    || !fits(policy, budget, 0.0) || !gate.allowReask(Trigger.CONTEXT_TOO_LONG)) {
                throw e;
            }
            List<ChatMessage> smaller = shrinker.shrink();
            if (smaller == null || smaller.isEmpty()) {
                throw e;
            }
            reasks = 1;
            listener.onReask(Trigger.CONTEXT_TOO_LONG, reasks);
            current = smaller;
            LOGGER.warnf("Prompt exceeded the model's context window; re-asking once with the history window halved (%d -> %d messages)",
                    messages.size(), smaller.size());
            try {
                first = asker.ask(current, null);
            } catch (LifecycleException second) {
                count("retry", "failed", Trigger.CONTEXT_TOO_LONG);
                throw second;
            }
            count("retry", "recovered", Trigger.CONTEXT_TOO_LONG);
        }
        return resolve(policy, first, current, asker, budget, gate, listener, reasks);
    }

    /**
     * As {@link #run} for a reply the caller already has (the tool loop's final
     * answer, or an earlier attempt).
     */
    Outcome resolve(Policy policy, Attempt first, List<ChatMessage> messages, Asker asker, RemainingBudget budget, ReaskGate gate,
                    ReaskListener listener)
            throws LifecycleException {
        return resolve(policy, first, messages, asker, budget, gate, listener, 0);
    }

    private Outcome resolve(Policy policy, Attempt first, List<ChatMessage> messages, Asker asker, RemainingBudget budget, ReaskGate gate,
                            ReaskListener listener, int alreadyReasked)
            throws LifecycleException {
        Attempt attempt = first;
        Map<String, Object> totalUsage = new LinkedHashMap<>();
        CascadingModelExecutor.mergeTokenUsage(totalUsage, tokenUsage(first));
        // Usage already spent on this model before the first reply (a context re-ask's
        // failed attempt reports none), so only the re-asks below count as retry cost.
        Map<String, Object> retryUsage = new LinkedHashMap<>();
        int reasks = alreadyReasked;
        boolean truncationReasked = false;

        while (true) {
            // A truncation that cannot be re-asked (already done, or no known cap to raise)
            // must not mask a corrective re-ask for the cut-off JSON it left behind.
            Detection detection = detect(policy, attempt, !truncationReasked && raisedCap(policy) != null);
            if (detection == null) {
                return new Outcome(withUsage(attempt, totalUsage), null, null, reasks, messages);
            }
            Trigger trigger = detection.trigger();
            String skip = null;
            if (reasks >= policy.maxRetries()) {
                skip = "skipped_budget";
            } else if (!gate.allowReask(trigger)) {
                skip = "skipped_breaker";
            } else if (!fits(policy, budget, retryCost(policy, retryUsage))) {
                skip = budget.remainingMs() < policy.minAttemptMs() ? "skipped_no_time" : "skipped_cost";
            }
            List<ChatMessage> request = messages;
            Integer maxTokens = null;
            if (skip == null && trigger == Trigger.TRUNCATION) {
                maxTokens = raisedCap(policy);
                if (truncationReasked || maxTokens == null) {
                    skip = "skipped_budget";
                }
            }
            if (skip == null && (trigger == Trigger.INVALID_JSON || trigger == Trigger.SCHEMA_MISMATCH)) {
                request = withCorrective(messages, attempt.text(), detection.reason(), policy.correctiveMessage());
            }
            if (skip != null) {
                count("retry", skip, trigger);
                LOGGER.debugf("Not re-asking (%s) for %s", skip, trigger.label());
                return new Outcome(withUsage(attempt, totalUsage), trigger, detection.reason(), reasks, messages);
            }

            reasks++;
            truncationReasked |= trigger == Trigger.TRUNCATION;
            listener.onReask(trigger, reasks);
            LOGGER.infof("Re-asking the same model (%s, re-ask %d of %d)", trigger.label(), reasks, policy.maxRetries());
            Attempt next;
            try {
                next = asker.ask(request, maxTokens);
            } catch (LifecycleException e) {
                if (e instanceof LifecycleException.LifecycleInterruptedException) {
                    throw e;
                }
                count("retry", "failed", trigger);
                LOGGER.warnf("Re-ask (%s) failed: %s — keeping the earlier reply", trigger.label(), e.getMessage());
                return new Outcome(withUsage(attempt, totalUsage), trigger, detection.reason(), reasks, messages);
            }
            CascadingModelExecutor.mergeTokenUsage(totalUsage, tokenUsage(next));
            CascadingModelExecutor.mergeTokenUsage(retryUsage, tokenUsage(next));
            attempt = next;
            Detection after = detect(policy, attempt, !truncationReasked && raisedCap(policy) != null);
            count("retry", after == null ? "recovered" : "still_invalid", trigger);
        }
    }

    /**
     * Whether a reply is still invalid JSON or off-shape, looked at directly — the
     * circuit breaker (R8) needs it for tasks whose policy does not re-ask. Returns
     * the detection ({@code INVALID_JSON} or {@code SCHEMA_MISMATCH} and EDDI's
     * reason), or null when the reply is usable (or this runner has no parser).
     */
    Detection classifyJsonReply(String text, String responseSchema, List<String> nonBlankFields, String taskId) {
        if (parser == null || text == null || text.isBlank()) {
            return null;
        }
        ModelOutputParser.JsonOutcome outcome = parser.parse(text, true, responseSchema, nonBlankFields, taskId);
        if (outcome.kind() == ModelOutputParser.Kind.INVALID) {
            return new Detection(Trigger.INVALID_JSON, outcome.reason());
        }
        if (outcome.kind() == ModelOutputParser.Kind.SCHEMA_MISMATCH) {
            return new Detection(Trigger.SCHEMA_MISMATCH, outcome.reason());
        }
        return null;
    }

    /** A rejected reply: which policy it breaks and EDDI's reason. */
    record Detection(Trigger trigger, String reason) {
    }

    /**
     * The first rejected property of the reply whose policy is {@code retry}, or
     * null when the reply is usable (or only fails policies that do not retry).
     */
    private Detection detect(Policy policy, Attempt attempt, boolean truncationActionable) {
        Detection truncation = null;
        String text = attempt.text();
        Map<String, Object> metadata = attempt.metadata();
        Object warning = metadata != null ? metadata.get("warning") : null;
        if (policy.retries(Trigger.EMPTY) && (text == null || text.isBlank())) {
            return new Detection(Trigger.EMPTY, "empty reply");
        }
        if (policy.retries(Trigger.TRUNCATION) && "truncated".equals(warning)) {
            truncation = new Detection(Trigger.TRUNCATION, "truncated reply");
            if (truncationActionable) {
                return truncation;
            }
        }
        if (policy.retries(Trigger.CONTENT_FILTER) && "content_filter".equals(warning)) {
            return new Detection(Trigger.CONTENT_FILTER, "filtered reply");
        }
        // A blank reply to a JSON task is not usable just because the empty policy does
        // not handle it: it is no object (a re-ask answered with a tool request or an
        // empty completion lands here).
        if (policy.retries(Trigger.INVALID_JSON) && policy.blankMayBeReaskedAsInvalidJson() && (text == null || text.isBlank())) {
            return new Detection(Trigger.INVALID_JSON, "empty reply");
        }
        if ((policy.retries(Trigger.INVALID_JSON) || policy.retries(Trigger.SCHEMA_MISMATCH)) && parser != null && text != null
                && !text.isBlank()) {
            ModelOutputParser.JsonOutcome outcome = parser.parse(text, true, policy.responseSchema(), policy.nonBlankFields(), policy.taskId());
            if (outcome.kind() == ModelOutputParser.Kind.INVALID && policy.retries(Trigger.INVALID_JSON)) {
                return new Detection(Trigger.INVALID_JSON, outcome.reason());
            }
            if (outcome.kind() == ModelOutputParser.Kind.SCHEMA_MISMATCH && policy.retries(Trigger.SCHEMA_MISMATCH)) {
                return new Detection(Trigger.SCHEMA_MISMATCH, outcome.reason());
            }
        }
        // nothing else wrong: a truncation that cannot be re-asked still ends
        // unresolved
        return truncation;
    }

    /**
     * True when this attempt (re-ask) may start: time left for a whole attempt and
     * the retry cost cap not reached.
     */
    private static boolean fits(Policy policy, RemainingBudget budget, double retryCostSoFar) {
        if (budget.remainingMs() < policy.minAttemptMs()) {
            return false;
        }
        return policy.maxRetryCostUsd() == null || retryCostSoFar < policy.maxRetryCostUsd();
    }

    private static double retryCost(Policy policy, Map<String, Object> retryUsage) {
        return TokenPricing.cost(policy.inputPricePer1M(), policy.outputPricePer1M(), retryUsage);
    }

    /**
     * The raised output cap for the truncation re-ask: base x factor, never above
     * {@link #MAX_OUTPUT_TOKENS_CEILING}; null when the base is unknown or already
     * at the ceiling (nothing to raise).
     */
    private static Integer raisedCap(Policy policy) {
        Integer base = policy.baseMaxOutputTokens();
        if (base == null || base <= 0 || base >= MAX_OUTPUT_TOKENS_CEILING) {
            return null;
        }
        long raised = Math.round(base * policy.truncationFactor());
        int capped = (int) Math.min(MAX_OUTPUT_TOKENS_CEILING, raised);
        return capped > base ? capped : null;
    }

    /**
     * Original messages, the model's bad reply as an assistant turn, and the
     * corrective user message ({@code {reason}} replaced literally — no template
     * engine, no model output, no user input in the user-role text).
     */
    static List<ChatMessage> withCorrective(List<ChatMessage> messages, String badReply, String reason, String correctiveTemplate) {
        List<ChatMessage> request = new ArrayList<>(messages);
        if (badReply != null && !badReply.isBlank()) {
            String echoed = badReply.length() > MAX_ECHOED_REPLY_CHARS ? badReply.substring(0, MAX_ECHOED_REPLY_CHARS) : badReply;
            request.add(AiMessage.from(echoed));
        }
        request.add(UserMessage.from(correctiveTemplate.replace("{reason}", reason)));
        return request;
    }

    /**
     * The base output-token cap a model runs with, from its parameters
     * ({@code maxTokens}, or {@code maxOutputTokens} for Gemini), the provider's
     * documented default where it has one, else null.
     */
    static Integer resolveBaseMaxOutputTokens(Map<String, String> params, String modelType) {
        if (params != null) {
            for (String key : List.of("maxTokens", "maxOutputTokens")) {
                String value = params.get(key);
                if (value != null && !value.isBlank()) {
                    try {
                        return Integer.valueOf(value.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
            }
        }
        return "anthropic".equalsIgnoreCase(modelType) ? AnthropicLanguageModelBuilder.DEFAULT_MAX_TOKENS : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tokenUsage(Attempt attempt) {
        Map<String, Object> metadata = attempt.metadata();
        return metadata != null && metadata.get("tokenUsage") instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    /** The attempt, its metadata carrying the usage summed over every attempt. */
    private static Attempt withUsage(Attempt attempt, Map<String, Object> totalUsage) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (attempt.metadata() != null) {
            metadata.putAll(attempt.metadata());
        }
        if (!totalUsage.isEmpty()) {
            metadata.put("tokenUsage", totalUsage);
        }
        return new Attempt(attempt.text(), metadata);
    }

    /** {@code eddi.llm.recovery{action,outcome,trigger}} — R6's meter. */
    void count(String action, String outcome, Trigger trigger) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.llm.recovery", "action", action, "outcome", outcome, "trigger", trigger.label()).increment();
        }
    }
}
