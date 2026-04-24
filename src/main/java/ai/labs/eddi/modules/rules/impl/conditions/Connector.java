/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.Rule;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class Connector implements IRuleCondition {
    public static final String ID = "connector";
    private final String operatorQualifier = "operator";

    public enum Operator {
        OR, AND
    }

    private Operator operator;

    private final List<IRuleCondition> conditions = new LinkedList<>();

    private Connector(Operator operator) {
        this.operator = operator;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put(operatorQualifier, operator.name());
        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(operatorQualifier)) {
                String operator = configs.get(operatorQualifier);
                if (operator.equals(Operator.AND.name())) {
                    this.operator = Operator.AND;
                } else {
                    this.operator = Operator.OR;
                }
            }
        }
    }

    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) throws Rule.InfiniteLoopException, Rule.RuntimeException {

        ExecutionState state;
        if (operator == Operator.OR) {
            state = ExecutionState.FAIL;

            for (IRuleCondition condition : conditions) {
                var executionState = condition.execute(memory, trace);
                if (executionState == ExecutionState.SUCCESS || executionState == ExecutionState.ERROR) {
                    state = executionState;
                    break;
                }
            }
        } else // if(operator == AND)
        {
            state = ExecutionState.SUCCESS;

            for (IRuleCondition condition : conditions) {
                var executionState = condition.execute(memory, trace);
                if (executionState == ExecutionState.FAIL || executionState == ExecutionState.ERROR) {
                    state = executionState;
                    break;
                }
            }
        }

        return state;
    }

    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    @Override
    public IRuleCondition clone() throws CloneNotSupportedException {
        Connector clone = new Connector(operator);

        List<IRuleCondition> conditionClone = new LinkedList<>();
        for (IRuleCondition condition : conditions) {
            conditionClone.add(condition.clone());
        }

        clone.setConditions(conditionClone);
        clone.setConfigs(getConfigs());

        return clone;
    }

    @Override
    public void setConditions(List<IRuleCondition> conditions) {
        this.conditions.addAll(conditions);
    }

    public Connector() {
    }

    public List<IRuleCondition> getConditions() {
        return conditions;
    }
}
