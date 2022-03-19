package ai.labs.eddi.modules.behavior.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.behavior.impl.conditions.IBehaviorCondition.ExecutionState.SUCCESS;

/**
 * @author ginccc
 */
public abstract class BaseMatcher implements IBehaviorCondition {
    private static final String KEY_OCCURRENCE = "occurrence";
    static final String KEY_EMPTY = "empty";

    @Getter
    @Setter
    protected ConversationStepOccurrence occurrence = ConversationStepOccurrence.currentStep;

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
                case "lastStep":
                    occurrence = ConversationStepOccurrence.lastStep;
                    break;
                case "anyStep":
                    occurrence = ConversationStepOccurrence.anyStep;
                    break;
                case "never":
                    occurrence = ConversationStepOccurrence.never;
                    break;
                case "currentStep":
                default:
                    occurrence = ConversationStepOccurrence.currentStep;
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
