/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.rules.impl.Rule;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * One template-data conversion per rule-set evaluation.
 * <p>
 * {@code dynamicvaluematcher} and {@code sizematcher} each called
 * {@code IMemoryItemConverter.convert(memory)} — which assembles properties,
 * context, the memory views and the conversation log — once per condition, per
 * turn. A rule set with twenty value checks converted the whole memory twenty
 * times. Memory does not change while rules are evaluated (conditions only read
 * it), so {@code RulesEvaluator} opens a scope for the evaluation and the
 * conditions share the first conversion. Outside a scope — or for a different
 * memory — a condition converts as before.
 * <p>
 * A {@link ScopedValue}, not a field: conditions are shared by every
 * conversation of the agent, and the binding ends with the evaluation.
 */
public final class TemplateDataScope {

    private static final ScopedValue<Cache> CURRENT = ScopedValue.newInstance();

    private TemplateDataScope() {
    }

    private static final class Cache {
        private final IConversationMemory memory;
        private Map<String, Object> templateData;
        private final Map<Rule, IRuleCondition.ExecutionState> ruleStates = new IdentityHashMap<>();

        private Cache(IConversationMemory memory) {
            this.memory = memory;
        }
    }

    /** Runs {@code op} with a conversion cache for {@code memory}. */
    public static <T> T call(IConversationMemory memory, ScopedValue.CallableOp<T, Exception> op) throws Exception {
        return ScopedValue.where(CURRENT, new Cache(memory)).call(op);
    }

    /**
     * The template data of {@code memory}: converted once per evaluation scope, or
     * freshly when no scope for this memory is open.
     */
    public static Map<String, Object> templateData(IConversationMemory memory, IMemoryItemConverter converter) {
        if (CURRENT.isBound()) {
            Cache cache = CURRENT.get();
            if (cache.memory == memory) {
                if (cache.templateData == null) {
                    cache.templateData = converter.convert(memory);
                }
                return cache.templateData;
            }
        }
        return converter.convert(memory);
    }

    /**
     * The state a rule already settled on in the open evaluation scope of
     * {@code memory}, or {@code null}. Memory does not change during an evaluation,
     * so a rule's outcome cannot either; {@code dependency} conditions use this to
     * evaluate a shared rule once instead of once per path leading to it, which for
     * rules that each depend twice on the previous one is the difference between
     * linear and exponential work.
     */
    public static IRuleCondition.ExecutionState ruleState(IConversationMemory memory, Rule rule) {
        if (CURRENT.isBound()) {
            Cache cache = CURRENT.get();
            if (cache.memory == memory) {
                return cache.ruleStates.get(rule);
            }
        }
        return null;
    }

    /**
     * Remembers {@code state} for {@code rule}; a no-op outside a matching scope.
     */
    public static void rememberRuleState(IConversationMemory memory, Rule rule, IRuleCondition.ExecutionState state) {
        if (CURRENT.isBound()) {
            Cache cache = CURRENT.get();
            if (cache.memory == memory) {
                cache.ruleStates.put(rule, state);
            }
        }
    }
}
