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
import java.util.*;

import static ai.labs.behavior.impl.extensions.IBehaviorExtension.ExecutionState.*;
import static ai.labs.behavior.impl.extensions.InputMatcher.ConversationStepOccurrence.*;

/**
 * @author ginccc
 */
public class InputMatcher implements IBehaviorExtension {
    private static final String ID = "inputmatcher";
    private static final String KEY_EXPRESSIONS = "expressions";
    private static final String KEY_EMPTY = "empty";
    private static final String KEY_OCCURRENCE = "occurrence";

    @Getter
    @Setter
    private List<Expression> expressions = Collections.emptyList();
    private final String expressionsQualifier = KEY_EXPRESSIONS;

    @Getter
    @Setter
    private ConversationStepOccurrence occurrence = currentStep;
    private final String conversationOccurrenceQualifier = KEY_OCCURRENCE;

    private ExecutionState state = NOT_EXECUTED;

    private IExpressionProvider expressionProvider;

    enum ConversationStepOccurrence {
        currentStep,
        lastStep,
        anyStep,
        never
    }

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
        HashMap<String, String> result = new HashMap<>();
        result.put(expressionsQualifier, StringUtilities.joinStrings(",", expressions));
        result.put(conversationOccurrenceQualifier, occurrence.toString());

        return result;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(expressionsQualifier)) {
                expressions = expressionProvider.parseExpressions(values.get(expressionsQualifier));
            }

            if (values.containsKey(conversationOccurrenceQualifier)) {
                String conversationOccurrence = values.get(conversationOccurrenceQualifier);
                switch (conversationOccurrence) {
                    case "currentStep":
                        occurrence = currentStep;
                        break;
                    case "lastStep":
                        occurrence = lastStep;
                        break;
                    case "anyStep":
                        occurrence = anyStep;
                        break;
                    case "never":
                        occurrence = never;
                        break;
                    default:
                        String errorMessage = "InputMatcher config param: " + conversationOccurrenceQualifier +
                                ". Needs to have one of the following values: %s, actual value: %s";
                        errorMessage = String.format(errorMessage,
                                Arrays.toString(ConversationStepOccurrence.values()), conversationOccurrence);
                        throw new IllegalArgumentException(errorMessage);
                }
            }
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
                state = occurredInAnyStep(memory) ? SUCCESS : FAIL;
                break;
            case never:
                state = occurredInAnyStep(memory) ? FAIL : SUCCESS;
                break;

        }

        return state;
    }

    private boolean occurredInAnyStep(IConversationMemory memory) {
        List<IData<String>> allLatestData = memory.getAllSteps().getAllLatestData(KEY_EXPRESSIONS);
        return allLatestData.stream().anyMatch(latestData -> evaluateInputExpressions(latestData) == SUCCESS);
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
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IBehaviorExtension clone() throws CloneNotSupportedException {
        IBehaviorExtension clone = new InputMatcher(expressionProvider);
        clone.setValues(getValues());
        return clone;
    }
}