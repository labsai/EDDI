/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * What a model phase spent before it failed, carried on the failure itself.
 * <p>
 * A task records its cost once its model phase has returned, from the result. A
 * phase that throws returns nothing, so the tool rounds, the priced tool calls
 * and the cascade steps it completed before the failure used to vanish from
 * {@code audit:cost} — the tenant's monthly counter and the conversation's
 * {@code maxCostPerConversationUsd} never saw them, and a caller who could
 * provoke a late failure got that spend unmetered. The pause exit already had
 * its carrier ({@code ToolApprovalRequiredException.addSpend}); this is the
 * same thing for the failure exit.
 * <p>
 * The layer that measured the spend attaches it ({@link #attach}) as a
 * suppressed throwable — so it survives being wrapped as a cause — and
 * {@code LlmTask} drains it ({@link #drain}) into the step on its way out. A
 * carrier is drained at most once: a cascade that absorbs a failed step's spend
 * into its own run totals drains it there, and attaches the run's totals to the
 * failure it finally throws, so nothing is counted twice.
 */
final class UnrecordedSpend extends RuntimeException {

    /**
     * The spend: tokens (the usual {@code tokenUsage} map) and their price, and the
     * cost of the tools the phase ran.
     */
    record Spend(Map<String, Object> tokenUsage, double llmCostUsd, double toolCostUsd) {

        static final Spend NONE = new Spend(Map.of(), 0.0, 0.0);

        double totalUsd() {
            return llmCostUsd + toolCostUsd;
        }

        boolean isEmpty() {
            return tokenUsage.isEmpty() && !(totalUsd() > 0.0);
        }
    }

    private final transient Spend spend;
    private final AtomicBoolean drained = new AtomicBoolean();

    private UnrecordedSpend(Spend spend) {
        super("unrecorded model-phase spend", null, false, false);
        this.spend = spend;
    }

    /**
     * Attaches spend to {@code failure}; nothing when there is none. Returns
     * {@code failure}, so a throw site can read
     * {@code throw attach(new X(..), ..)}.
     */
    static <T extends Throwable> T attach(T failure, Map<String, Object> tokenUsage, double llmCostUsd, double toolCostUsd) {
        Spend spend = new Spend(tokenUsage != null ? Map.copyOf(withoutNullValues(tokenUsage)) : Map.of(), finite(llmCostUsd),
                finite(toolCostUsd));
        if (failure != null && !spend.isEmpty()) {
            failure.addSuppressed(new UnrecordedSpend(spend));
        }
        return failure;
    }

    /**
     * Takes every spend carried by {@code failure}, its causes and their suppressed
     * throwables that has not been taken yet, summed. Never null.
     */
    static Spend drain(Throwable failure) {
        Map<String, Object> tokens = new LinkedHashMap<>();
        double llm = 0.0;
        double tools = 0.0;
        boolean any = false;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            for (Throwable suppressed : t.getSuppressed()) {
                if (suppressed instanceof UnrecordedSpend carrier && carrier.drained.compareAndSet(false, true)) {
                    CascadingModelExecutor.mergeTokenUsage(tokens, carrier.spend.tokenUsage());
                    llm += carrier.spend.llmCostUsd();
                    tools += carrier.spend.toolCostUsd();
                    any = true;
                }
            }
        }
        return any ? new Spend(tokens, llm, tools) : Spend.NONE;
    }

    private static double finite(double usd) {
        return Double.isFinite(usd) && usd > 0.0 ? usd : 0.0;
    }

    private static Map<String, Object> withoutNullValues(Map<String, Object> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return copy;
    }
}
