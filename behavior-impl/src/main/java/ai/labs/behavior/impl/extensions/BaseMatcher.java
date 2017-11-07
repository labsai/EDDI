package ai.labs.behavior.impl.extensions;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.behavior.impl.extensions.BaseMatcher.ConversationStepOccurrence.*;
import static ai.labs.behavior.impl.extensions.IBehaviorExtension.ExecutionState.NOT_EXECUTED;
import static ai.labs.behavior.impl.extensions.IBehaviorExtension.ExecutionState.SUCCESS;

/**
 * @author ginccc
 */
@Slf4j
public abstract class BaseMatcher implements IBehaviorExtension {
    private static final String KEY_OCCURRENCE = "occurrence";
    static final String KEY_EMPTY = "empty";

    @Getter
    @Setter
    protected ConversationStepOccurrence occurrence = currentStep;

    private final String conversationOccurrenceQualifier = KEY_OCCURRENCE;
    protected ExecutionState state = NOT_EXECUTED;

    enum ConversationStepOccurrence {
        currentStep,
        lastStep,
        anyStep,
        never
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IBehaviorExtension clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException("Should be Overridden by Subclass!");
    }

    @Override
    public Map<String, String> getValues() {
        Map<String, String> result = new HashMap<>();
        result.put(conversationOccurrenceQualifier, occurrence.toString());

        return result;
    }

    void setConversationOccurrenceQualifier(Map<String, String> values) {
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
                    String errorMessage = getId() + " config param: " + conversationOccurrenceQualifier +
                            ". Needs to have one of the following values: %s, actual value: '%s'.\n" +
                            "'currentStep' has been set as default now.";
                    errorMessage = String.format(errorMessage, Arrays.toString(values()), conversationOccurrence);
                    log.error(errorMessage, new IllegalArgumentException(errorMessage));
            }
        }
    }

    boolean occurredInAnyStep(IConversationMemory memory, String dataKey, ValueEvaluation valueEvaluation) {
        List<IData<String>> allLatestData = memory.getAllSteps().getAllLatestData(dataKey);
        return allLatestData.stream().anyMatch(latestData -> valueEvaluation.evaluate(latestData) == SUCCESS);
    }

    interface ValueEvaluation {
        ExecutionState evaluate(IData data);
    }
}
