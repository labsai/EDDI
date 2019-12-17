package ai.labs.behavior.impl.conditions;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.behavior.impl.conditions.BaseMatcher.ConversationStepOccurrence.anyStep;
import static ai.labs.behavior.impl.conditions.BaseMatcher.ConversationStepOccurrence.currentStep;
import static ai.labs.behavior.impl.conditions.BaseMatcher.ConversationStepOccurrence.lastStep;
import static ai.labs.behavior.impl.conditions.BaseMatcher.ConversationStepOccurrence.never;
import static ai.labs.behavior.impl.conditions.BaseMatcher.ConversationStepOccurrence.values;
import static ai.labs.behavior.impl.conditions.IBehaviorCondition.ExecutionState.SUCCESS;

/**
 * @author ginccc
 */
@Slf4j
public abstract class BaseMatcher implements IBehaviorCondition {
    private static final String KEY_OCCURRENCE = "occurrence";
    static final String KEY_EMPTY = "empty";

    @Getter
    @Setter
    protected ConversationStepOccurrence occurrence = currentStep;

    private final String conversationOccurrenceQualifier = KEY_OCCURRENCE;


    enum ConversationStepOccurrence {
        currentStep,
        lastStep,
        anyStep,
        never
    }

    @Override
    public IBehaviorCondition clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException("Should be Overridden by Subclass!");
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put(conversationOccurrenceQualifier, occurrence.toString());

        return configs;
    }

    void setConversationOccurrenceQualifier(Map<String, String> configs) {
        if (configs.containsKey(conversationOccurrenceQualifier)) {
            String conversationOccurrence = configs.get(conversationOccurrenceQualifier);
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
                            ". Needs to have one of the following configs: %s, actual value: '%s'.\n" +
                            "'currentStep' has been set as default now.";
                    errorMessage = String.format(errorMessage, Arrays.toString(values()), conversationOccurrence);
                    log.warn(errorMessage, new IllegalArgumentException(errorMessage));
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
