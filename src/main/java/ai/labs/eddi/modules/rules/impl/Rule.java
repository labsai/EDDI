/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition;
import ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class Rule implements Cloneable {
    private String name;
    private List<String> actions = new LinkedList<>();
    private List<IRuleCondition> conditions = new LinkedList<>();

    public Rule(String name) {
        this.name = name;
    }

    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) throws InfiniteLoopException, RuntimeException {
        if (trace.contains(this)) {
            // this is an infinite loop, thus throw error
            throw throwInfiniteLoopError(trace);
        } else {
            trace.add(this);

            ExecutionState state = ExecutionState.NOT_EXECUTED;
            for (IRuleCondition condition : conditions) {
                var executionState = condition.execute(memory, trace);
                if (executionState == ExecutionState.FAIL || executionState == ExecutionState.ERROR) {
                    state = executionState;
                    break;
                }
            }

            List<Rule> tmp = new LinkedList<>(trace);
            trace.clear();
            trace.addAll(tmp.subList(0, tmp.indexOf(this)));

            if (state != ExecutionState.NOT_EXECUTED) {
                return state;
            }

            return ExecutionState.SUCCESS;
        }
    }

    private InfiniteLoopException throwInfiniteLoopError(List<Rule> trace) {
        StringBuilder errorMessage = new StringBuilder();

        errorMessage.append("reached infinite  loop:\n");
        for (Rule status : trace) {
            errorMessage.append(" -> ").append(status.getName()).append("\n");
        }

        return new InfiniteLoopException(errorMessage.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof Rule))
            return false;

        Rule that = (Rule) o;

        return name.equals(that.getName());
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(name);
    }

    @Override
    public Rule clone() throws CloneNotSupportedException {
        Rule clone = new Rule(name);

        List<IRuleCondition> executablesClone = new LinkedList<>();
        for (IRuleCondition condition : conditions) {
            IRuleCondition exClone = condition.clone();
            executablesClone.add(exClone);
        }
        clone.setConditions(executablesClone);

        return clone;
    }

    @Override
    public String toString() {
        return name;
    }

    public static class RuntimeException extends Exception {
        public RuntimeException(String message, Exception e) {
            super(message, e);
        }
    }

    public class InfiniteLoopException extends Exception {
        InfiniteLoopException(String message) {
            super(message);
        }
    }

    public Rule() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public List<IRuleCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<IRuleCondition> conditions) {
        this.conditions = conditions;
    }
}
