/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.utils.StringUtilities;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import static ai.labs.eddi.engine.memory.MemoryKeys.EXPRESSIONS_PARSED;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.*;

/**
 * @author ginccc
 */

public class InputMatcher extends BaseMatcher {
    public static final String ID = "inputmatcher";
    private static final String KEY_EXPRESSIONS = "expressions";

    private Expressions expressions = new Expressions();
    private final String expressionsQualifier = KEY_EXPRESSIONS;

    private final IExpressionProvider expressionProvider;

    public InputMatcher(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put(expressionsQualifier, StringUtilities.joinStrings(",", expressions));
        configs.putAll(super.getConfigs());

        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(expressionsQualifier)) {
                expressions = expressionProvider.parseExpressions(configs.get(expressionsQualifier));
            }

            setConversationOccurrenceQualifier(configs);
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) {
        IData<String> data;
        ExecutionState state = NOT_EXECUTED;
        switch (occurrence) {
            case currentStep -> {
                data = memory.getCurrentStep().getLatestData(EXPRESSIONS_PARSED);
                state = evaluateInputExpressions(data);
            }
            case lastStep -> {
                IConversationStepStack previousSteps = memory.getPreviousSteps();
                if (previousSteps.size() > 0) {
                    data = previousSteps.get(0).getLatestData(EXPRESSIONS_PARSED);
                    state = evaluateInputExpressions(data);
                } else {
                    state = FAIL;
                }
            }
            case anyStep -> state = occurredInAnyStep(memory, EXPRESSIONS_PARSED.key(), this::evaluateInputExpressions) ? SUCCESS : FAIL;
            case never -> state = occurredInAnyStep(memory, EXPRESSIONS_PARSED.key(), this::evaluateInputExpressions) ? FAIL : SUCCESS;
            default -> {
            }
        }

        return state;
    }

    private ExecutionState evaluateInputExpressions(IData<String> data) {
        Expressions inputExpressions = new Expressions();
        if (data != null && data.getResult() != null) {
            inputExpressions = expressionProvider.parseExpressions(data.getResult());
        }

        if (isInputEmpty(inputExpressions) || Collections.indexOfSubList(inputExpressions, expressions) > -1) {
            return SUCCESS;
        } else {
            return FAIL;
        }
    }

    private boolean isInputEmpty(Expressions inputExpressions) {
        return expressions.size() == 1 && expressions.get(0).getExpressionName().equals(KEY_EMPTY) && inputExpressions.size() == 0;
    }

    @Override
    public IRuleCondition clone() {
        IRuleCondition clone = new InputMatcher(expressionProvider);
        clone.setConfigs(getConfigs());
        return clone;
    }

    public Expressions getExpressions() {
        return expressions;
    }

    public void setExpressions(Expressions expressions) {
        this.expressions = expressions;
    }
}