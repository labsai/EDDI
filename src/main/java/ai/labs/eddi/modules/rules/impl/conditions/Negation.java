/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.Rule;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.ERROR;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.FAIL;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.NOT_EXECUTED;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.SUCCESS;

/**
 * Inverts the outcome of its children. As documented in
 * {@code docs/behavior-rules.md}, all children are {@code AND}-combined first
 * and the combined result is then inverted — a negation with two children that
 * both succeed evaluates to {@code FAIL}, a negation whose first child fails
 * evaluates to {@code SUCCESS}.
 *
 * @author ginccc
 */

public class Negation implements IRuleCondition {
    public static final String ID = "negation";

    private final List<IRuleCondition> conditions = new LinkedList<>();

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<IRuleCondition> getConditions() {
        return Collections.unmodifiableList(conditions);
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) throws Rule.InfiniteLoopException, Rule.RuntimeException {
        if (conditions.isEmpty()) {
            return NOT_EXECUTED;
        }

        for (IRuleCondition condition : conditions) {
            var stateOfExecutable = condition.execute(memory, trace);
            if (stateOfExecutable == ERROR || stateOfExecutable == NOT_EXECUTED) {
                // nothing meaningful to invert — propagate as is
                return stateOfExecutable;
            }

            if (stateOfExecutable == FAIL) {
                // the AND-combination is already false, thus the negation is true
                return SUCCESS;
            }
        }

        // every child succeeded, thus the AND-combination is true and the negation
        // false
        return FAIL;
    }

    @Override
    public void validateConfiguration() {
        if (conditions.isEmpty()) {
            throw new IllegalArgumentException("'" + ID + "' requires at least one nested condition to invert.");
        }
    }

    @Override
    public IRuleCondition clone() throws CloneNotSupportedException {
        Negation negation = new Negation();
        List<IRuleCondition> conditionsClone = new LinkedList<>();
        for (IRuleCondition condition : conditions) {
            conditionsClone.add(condition.clone());
        }
        negation.setConditions(conditionsClone);
        return negation;
    }

    @Override
    public void setConditions(List<IRuleCondition> conditions) {
        this.conditions.clear();
        if (conditions != null) {
            this.conditions.addAll(conditions);
        }
    }

    public Negation() {
    }

    public void setCondition(IRuleCondition condition) {
        this.conditions.clear();
        if (condition != null) {
            this.conditions.add(condition);
        }
    }
}
