/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.ToolQuotaConfig;
import ai.labs.eddi.modules.templating.ITemplatingEngine;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * The quota reservation of ONE tool call.
 * <p>
 * Created per call by {@link ToolQuotaService#gateFor} and used by
 * {@code ToolExecutionService} in two steps around the execution:
 * <ol>
 * <li>{@link #reserve()} immediately before the tool runs — after the rate
 * limit and the cache have been consulted, so a call that never runs never
 * counts;</li>
 * <li>{@link #settle} once it has run: a call that failed gives its reservation
 * back.</li>
 * </ol>
 * A call whose outcome is unknown — a timeout abandons the worker but cannot
 * stop it — keeps its count, because the tool may still complete its side
 * effect. Not shared between threads.
 */
public final class ToolQuotaGate {

    private final ToolQuotaService service;
    private final ToolQuotaConfig config;
    private final ToolQuotaRules.Resolved rule;
    private final String tool;
    private final IConversationMemory memory;
    private final Predicate<String> failureDetector;

    private ToolQuotaKey reservedKey;
    private boolean refused;

    ToolQuotaGate(ToolQuotaService service, ToolQuotaConfig config, ToolQuotaRules.Resolved rule, String tool, IConversationMemory memory,
            Predicate<String> failureDetector) {
        this.service = service;
        this.config = config;
        this.rule = rule;
        this.tool = tool;
        this.memory = memory;
        this.failureDetector = failureDetector;
    }

    /** A gate for a stored rule that cannot be applied: it refuses everything. */
    static ToolQuotaGate unusable(ToolQuotaService service, String tool) {
        return new ToolQuotaGate(service, null, null, tool, null, null);
    }

    /**
     * Counts the call, if the quota allows.
     *
     * @return empty when the call was counted and may run; otherwise the refusal to
     *         hand the model in place of a result
     */
    public Optional<String> reserve() {
        if (rule == null) {
            refused = true;
            service.count(tool, "unavailable");
            return Optional.of(ToolQuotaService.unavailableRefusal());
        }
        try {
            ToolQuotaKey key = service.keyFor(rule, config, memory);
            IToolQuotaStore.Acquisition acquisition = service.store().tryAcquire(key, rule.limit(), service.expiresAt(key, rule));
            if (acquisition.granted()) {
                reservedKey = key;
                service.count(tool, "allowed");
                return Optional.empty();
            }
            refused = true;
            service.count(tool, "refused");
            return Optional.of(service.overLimitRefusal(rule, service.resetAt(key, rule)));
        } catch (RuntimeException | ITemplatingEngine.TemplateEngineException e) {
            refused = true;
            service.failedToCheck(tool, memory, "reserve", e);
            return Optional.of(ToolQuotaService.unavailableRefusal());
        }
    }

    /**
     * Settles a reservation once the tool has run: gives it back when the call
     * failed, keeps it when it succeeded. A no-op when nothing was reserved.
     *
     * @param result
     *            the tool's raw result, or null when it threw
     * @param failure
     *            what it threw, or null
     */
    public void settle(String result, Throwable failure) {
        if (reservedKey == null) {
            return;
        }
        boolean failed = failure != null || (result != null && failureDetector != null && failureDetector.test(result));
        if (!failed) {
            return;
        }
        ToolQuotaKey key = reservedKey;
        reservedKey = null;
        try {
            service.store().release(key);
            service.count(tool, "released");
        } catch (RuntimeException e) {
            // The count stays — the conservative side. The tool already ran; a failure here
            // must not replace its result.
            service.countStoreFailure("release");
        }
    }

    /** True when {@link #reserve()} refused the call: it was not run. */
    public boolean refused() {
        return refused;
    }
}
