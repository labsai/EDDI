/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.rules.impl.RuleSet;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public interface IRuleCondition extends Cloneable {
    String CONDITION_PREFIX = "ai.labs.behavior.conditions.";

    String getId();

    default Map<String, String> getConfigs() {
        return Collections.emptyMap();
    }

    default void setConfigs(Map<String, String> configs) {
        // not implemented
    }

    default List<IRuleCondition> getConditions() {
        return Collections.emptyList();
    }

    default void setConditions(List<IRuleCondition> conditions) {
        // not implemented
    }

    default void setContainingRuleSet(RuleSet behaviorSet) {
        // not implemented
    }

    enum ExecutionState {
        SUCCESS, FAIL, NOT_EXECUTED, ERROR
    }

    ExecutionState execute(IConversationMemory memory, List<Rule> trace) throws Rule.InfiniteLoopException, Rule.RuntimeException;

    IRuleCondition clone() throws CloneNotSupportedException;
}
