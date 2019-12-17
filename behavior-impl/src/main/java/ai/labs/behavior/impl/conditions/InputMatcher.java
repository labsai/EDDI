package ai.labs.behavior.impl.conditions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.utilities.StringUtilities;
import lombok.Getter;
import lombok.Setter;

import javax.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.behavior.impl.conditions.IBehaviorCondition.ExecutionState.FAIL;
import static ai.labs.behavior.impl.conditions.IBehaviorCondition.ExecutionState.NOT_EXECUTED;
import static ai.labs.behavior.impl.conditions.IBehaviorCondition.ExecutionState.SUCCESS;
import static ai.labs.memory.IConversationMemory.IConversationStepStack;

/**
 * @author ginccc
 */
public class InputMatcher extends BaseMatcher implements IBehaviorCondition {
    private static final String ID = "inputmatcher";
    private static final String KEY_EXPRESSIONS = "expressions";

    @Getter
    @Setter
    private Expressions expressions = new Expressions();
    private final String expressionsQualifier = KEY_EXPRESSIONS;

    private IExpressionProvider expressionProvider;

    @Inject
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
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        IData<String> data;
        ExecutionState state = NOT_EXECUTED;
        switch (occurrence) {
            case currentStep:
                data = memory.getCurrentStep().getLatestData(KEY_EXPRESSIONS);
                state = evaluateInputExpressions(data);
                break;
            case lastStep:
                IConversationStepStack previousSteps = memory.getPreviousSteps();
                if (previousSteps.size() > 0) {
                    data = previousSteps.get(0).getLatestData(KEY_EXPRESSIONS);
                    state = evaluateInputExpressions(data);
                } else {
                    state = FAIL;
                }
                break;
            case anyStep:
                state = occurredInAnyStep(memory, KEY_EXPRESSIONS, this::evaluateInputExpressions) ? SUCCESS : FAIL;
                break;
            case never:
                state = occurredInAnyStep(memory, KEY_EXPRESSIONS, this::evaluateInputExpressions) ? FAIL : SUCCESS;
                break;
        }

        return state;
    }

    private ExecutionState evaluateInputExpressions(IData<String> data) {
        Expressions inputExpressions = new Expressions();
        if (data != null && data.getResult() != null) {
            inputExpressions = expressionProvider.parseExpressions(data.getResult());
        }

        if (isInputEmpty(inputExpressions) ||
                Collections.indexOfSubList(inputExpressions, expressions) > -1) {
            return SUCCESS;
        } else {
            return FAIL;
        }
    }

    private boolean isInputEmpty(Expressions inputExpressions) {
        return expressions.size() == 1 &&
                expressions.get(0).getExpressionName().equals(KEY_EMPTY) &&
                inputExpressions.size() == 0;
    }

    @Override
    public IBehaviorCondition clone() {
        IBehaviorCondition clone = new InputMatcher(expressionProvider);
        clone.setConfigs(getConfigs());
        return clone;
    }
}