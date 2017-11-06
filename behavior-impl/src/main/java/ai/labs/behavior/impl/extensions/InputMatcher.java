package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.expressions.Expression;
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

import static ai.labs.behavior.impl.extensions.IBehaviorExtension.ExecutionState.FAIL;
import static ai.labs.behavior.impl.extensions.IBehaviorExtension.ExecutionState.SUCCESS;

/**
 * @author ginccc
 */
public class InputMatcher extends BaseMatcher implements IBehaviorExtension {
    private static final String ID = "inputmatcher";
    private static final String KEY_EXPRESSIONS = "expressions";

    @Getter
    @Setter
    private List<Expression> expressions = Collections.emptyList();
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
    public Map<String, String> getValues() {
        Map<String, String> result = new HashMap<>();
        result.put(expressionsQualifier, StringUtilities.joinStrings(",", expressions.toArray()));
        result.putAll(super.getValues());

        return result;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(expressionsQualifier)) {
                expressions = expressionProvider.parseExpressions(values.get(expressionsQualifier));
            }

            setConversationOccurrenceQualifier(values);
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        IData<String> data;
        switch (occurrence) {
            case currentStep:
                data = memory.getCurrentStep().getLatestData(KEY_EXPRESSIONS);
                state = evaluateInputExpressions(data);
                break;
            case lastStep:
                data = memory.getPreviousSteps().get(0).getLatestData(KEY_EXPRESSIONS);
                state = evaluateInputExpressions(data);
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
        List<Expression> inputExpressions = Collections.emptyList();
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

    private boolean isInputEmpty(List<Expression> inputExpressions) {
        return expressions.size() == 1 &&
                expressions.get(0).getExpressionName().equals(KEY_EMPTY) &&
                inputExpressions.size() == 0;
    }

    @Override
    public IBehaviorExtension clone() throws CloneNotSupportedException {
        IBehaviorExtension clone = new InputMatcher(expressionProvider);
        clone.setValues(getValues());
        return clone;
    }
}