/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl.orchestration;

import ai.labs.eddi.modules.llm.impl.LlmTask;
import ai.labs.eddi.modules.llm.impl.TokenCounterFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.TokenCountEstimator;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Metrics;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * In-turn tool-context budget enforcement (D6b): meters the token cost of
 * accumulated tool exchanges within a single turn and evicts the oldest whole
 * exchanges when they exceed {@code maxToolContextTokens}, plus the TokenUsage
 * accumulation/reporting helpers the tool-call loop shares with
 * {@code LegacyChatExecutor}/{@code CascadingModelExecutor}. Extracted from
 * {@code AgentOrchestrator} (R2 step 3) as a pure move — no behavior change.
 * <p>
 * Constructed once in {@code AgentOrchestrator}'s own constructor, holding
 * {@link TokenCounterFactory} by reference (the SAME instance {@code LlmTask}
 * uses to window conversation history — one accounting rule for both halves of
 * the request). Everything else in this class is static and self-contained;
 * only {@link #resolveToolContextEstimator} needs the held dependency.
 */
public class ToolContextBudget {

    private static final Logger LOGGER = Logger.getLogger(ToolContextBudget.class);

    /**
     * Fallback for {@code LlmConfiguration.Task#getMaxToolContextTokens()} when a
     * stored config carries an explicit {@code null} (the POJO's own field default
     * is the same number, so this only covers deserialized nulls).
     */
    public static final int DEFAULT_MAX_TOOL_CONTEXT_TOKENS = 60_000;

    /**
     * The three token-usage count keys of the map {@link #tokenUsageMap} produces,
     * in reporting order. Shared by every consumer that merges usage maps.
     */
    public static final List<String> TOKEN_USAGE_FIELDS = List.of("inputTokens", "outputTokens", "totalTokens");

    private final TokenCounterFactory tokenCounterFactory;

    public ToolContextBudget(TokenCounterFactory tokenCounterFactory) {
        this.tokenCounterFactory = tokenCounterFactory;
    }

    /**
     * Resolves the estimator used to meter the in-turn tool context, reusing
     * {@link TokenCounterFactory} so the tool half of a request is counted by the
     * same rule as the history half.
     * <p>
     * The task's declared type/model may still carry unresolved global-variable
     * references at this point ({@code LlmTask} resolves them for the model lookup,
     * not for the task POJO), and an unknown model name can make a provider
     * tokenizer refuse to construct. Both outcomes fall back to the approximate
     * chars/4 estimator rather than propagating: a safety ceiling that throws is
     * worse than a slightly imprecise one.
     */
    public TokenCountEstimator resolveToolContextEstimator(LlmConfiguration.Task task) {
        try {
            Map<String, String> parameters = task.getParameters();
            String modelName = parameters != null ? LlmTask.resolveModelName(parameters) : null;
            return tokenCounterFactory.getEstimator(task.getType(), modelName);
        } catch (Exception e) {
            LOGGER.debugf("Tool-context budget: falling back to the approximate token estimator (%s)", e.getMessage());
            return tokenCounterFactory.getEstimator(null, null);
        }
    }

    /**
     * Drops the oldest complete tool exchanges from {@code messages} until the
     * accumulated in-turn tool context fits inside {@code budgetTokens}.
     *
     * <p>
     * A <em>tool exchange</em> is an {@link AiMessage} carrying tool-execution
     * requests plus the run of {@link ToolExecutionResultMessage}s that answers it.
     * Eviction operates on whole exchanges and nothing else, which is the entire
     * point: dropping a result without its requesting {@code AiMessage} leaves an
     * unanswerable {@code tool_call_id}, and dropping the {@code AiMessage} without
     * its results leaves a tool call the provider will reject — the eviction would
     * then <em>cause</em> the 400 it exists to prevent. System, user and
     * assistant-prose messages are never candidates; conversation history is
     * governed by {@code maxContextTokens}/{@code conversationHistoryLimit}, not by
     * this guard.
     * </p>
     *
     * <p>
     * The most recent exchange is never evicted. When it alone exceeds the budget
     * there is nothing eviction can do — the model asked for those results and must
     * see them — so the method logs the overrun and lets the request through
     * unchanged, exactly as today. That case is what {@code toolResponseLimits} is
     * for.
     * </p>
     *
     * <p>
     * Eviction is silent to the model on purpose: no gap-marker message is
     * injected. A {@code SystemMessage} mid-transcript is not portable across the
     * twelve supported providers (several hoist system content to a dedicated
     * top-level field), and a {@code UserMessage} would fabricate a turn the user
     * never took. The loss is instead reported to the agent designer through the
     * execution trace, a Micrometer counter and a WARN log.
     * </p>
     *
     * <p>
     * Static and fully parameterized — the orchestrator is an application-scoped
     * singleton and holds no per-conversation state.
     * </p>
     *
     * @param messages
     *            the live message list; mutated in place
     * @param budgetTokens
     *            the aggregate ceiling; callers skip this method entirely when it
     *            is not positive
     * @param tokenMemo
     *            per-attempt cache of message → token count
     * @param trace
     *            execution trace; receives one {@code tool_context_evicted} entry
     *            per eviction round
     */
    public static void enforceToolContextBudget(List<ChatMessage> messages, int budgetTokens, TokenCountEstimator estimator,
                                                Map<ChatMessage, Integer> tokenMemo, List<Map<String, Object>> trace,
                                                String conversationId) {

        List<int[]> exchanges = findToolExchanges(messages);
        // Nothing to trade away: with one exchange (or none) the only candidate is the
        // one the model is waiting on.
        if (exchanges.size() < 2) {
            return;
        }

        int total = 0;
        for (int[] range : exchanges) {
            for (int i = range[0]; i <= range[1]; i++) {
                total += tokensOf(messages.get(i), estimator, tokenMemo);
            }
        }
        if (total <= budgetTokens) {
            return;
        }

        int tokensBefore = total;
        int evictedExchanges = 0;
        int evictedMessages = 0;
        while (total > budgetTokens && evictedExchanges < exchanges.size() - 1) {
            int[] range = exchanges.get(evictedExchanges);
            for (int i = range[0]; i <= range[1]; i++) {
                total -= tokensOf(messages.get(i), estimator, tokenMemo);
            }
            evictedMessages += range[1] - range[0] + 1;
            evictedExchanges++;
        }

        // Remove highest index first so the earlier ranges stay valid. Ranges are
        // removed individually rather than as one span so that anything sitting
        // between two exchanges is left untouched.
        for (int k = evictedExchanges - 1; k >= 0; k--) {
            int[] range = exchanges.get(k);
            messages.subList(range[0], range[1] + 1).clear();
        }

        boolean withinBudget = total <= budgetTokens;

        Map<String, Object> evictionStep = new HashMap<>();
        evictionStep.put("type", "tool_context_evicted");
        evictionStep.put("evictedExchanges", evictedExchanges);
        evictionStep.put("evictedMessages", evictedMessages);
        evictionStep.put("tokensBefore", tokensBefore);
        evictionStep.put("tokensAfter", total);
        evictionStep.put("budgetTokens", budgetTokens);
        evictionStep.put("withinBudget", withinBudget);
        trace.add(evictionStep);

        try {
            Metrics.globalRegistry.counter("eddi.llm.tool_context.evictions",
                    "outcome", withinBudget ? "within_budget" : "still_over_budget").increment(evictedExchanges);
        } catch (Exception e) {
            LOGGER.debugf("tool-context eviction metric emit failed: %s", e.getMessage());
        }

        LOGGER.warnf("llm.tool_context.evicted: conversation '%s' accumulated %d tokens of tool context against "
                + "maxToolContextTokens=%d; dropped the %d oldest tool exchange(s) (%d messages), now %d tokens.%s "
                + "The model can no longer see those tool results — lower maxToolIterations, set toolResponseLimits, "
                + "or raise maxToolContextTokens.",
                sanitize(conversationId), tokensBefore, budgetTokens, evictedExchanges, evictedMessages, total,
                withinBudget ? "" : " STILL OVER BUDGET: the most recent exchange alone exceeds the ceiling and is never evicted.");
    }

    /**
     * Locates every tool exchange in message order. Each entry is an inclusive
     * {@code [start, end]} index pair whose {@code start} is an {@link AiMessage}
     * with tool-execution requests and whose {@code end} is the last
     * {@link ToolExecutionResultMessage} immediately following it.
     */
    private static List<int[]> findToolExchanges(List<ChatMessage> messages) {
        List<int[]> exchanges = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof AiMessage ai && ai.hasToolExecutionRequests()) {
                int end = i;
                while (end + 1 < messages.size() && messages.get(end + 1) instanceof ToolExecutionResultMessage) {
                    end++;
                }
                exchanges.add(new int[]{i, end});
                i = end;
            }
        }
        return exchanges;
    }

    /** Memoized token count for one message, falling back to chars/4 on failure. */
    private static int tokensOf(ChatMessage message, TokenCountEstimator estimator, Map<ChatMessage, Integer> memo) {
        Integer cached = memo.get(message);
        if (cached != null) {
            return cached;
        }
        String text = TokenCounterFactory.extractText(message);
        int tokens;
        try {
            tokens = estimator.estimateTokenCountInText(text);
        } catch (Exception e) {
            // A provider tokenizer that rejects a payload must not abort the turn; an
            // approximate count still keeps the ceiling meaningful.
            tokens = text.length() / 4;
        }
        memo.put(message, tokens);
        return tokens;
    }

    /**
     * Sum two (possibly null) TokenUsage values field-by-field, tolerating nulls.
     */
    public static TokenUsage sumTokens(TokenUsage a, TokenUsage b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return new TokenUsage(sumInt(a.inputTokenCount(), b.inputTokenCount()), sumInt(a.outputTokenCount(), b.outputTokenCount()),
                sumInt(a.totalTokenCount(), b.totalTokenCount()));
    }

    private static Integer sumInt(Integer a, Integer b) {
        return (a != null ? a : 0) + (b != null ? b : 0);
    }

    /**
     * Convert a TokenUsage into a template/audit-friendly map with non-null counts.
     */
    public static Map<String, Object> tokenUsageMap(TokenUsage usage) {
        Map<String, Object> map = new HashMap<>();
        map.put("inputTokens", usage.inputTokenCount() != null ? usage.inputTokenCount() : 0);
        map.put("outputTokens", usage.outputTokenCount() != null ? usage.outputTokenCount() : 0);
        map.put("totalTokens", usage.totalTokenCount() != null ? usage.totalTokenCount() : 0);
        return map;
    }
}
