package ai.labs.eddi.modules.behavior.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.behavior.impl.BehaviorRule;
import ai.labs.eddi.utils.StringUtilities;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.labs.eddi.modules.behavior.impl.conditions.IBehaviorCondition.ExecutionState.*;

/**
 * @author ginccc
 */
public class ActionMatcher extends BaseMatcher implements IBehaviorCondition {
    public static final String ID = "actionmatcher";
    private static final String KEY_ACTIONS = "actions";

    @Getter
    @Setter
    private List<String> actions = Collections.emptyList();
    private final String actionsQualifier = KEY_ACTIONS;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put(actionsQualifier, StringUtilities.joinStrings(",", actions));
        configs.putAll(super.getConfigs());

        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(actionsQualifier)) {
                actions = convertToActions(configs.get(actionsQualifier));
            }

            setConversationOccurrenceQualifier(configs);
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        IData<List<String>> data;
        ExecutionState state = NOT_EXECUTED;
        switch (occurrence) {
            case currentStep -> {
                data = memory.getCurrentStep().getLatestData(KEY_ACTIONS);
                state = evaluateActions(data);
            }
            case lastStep -> {
                IConversationMemory.IConversationStepStack previousSteps = memory.getPreviousSteps();
                if (previousSteps.size() > 0) {
                    data = previousSteps.get(0).getLatestData(KEY_ACTIONS);
                    state = evaluateActions(data);
                } else {
                    state = FAIL;
                }
            }
            case anyStep -> state = occurredInAnyStep(memory, KEY_ACTIONS, this::evaluateActions) ? SUCCESS : FAIL;
            case never -> state = occurredInAnyStep(memory, KEY_ACTIONS, this::evaluateActions) ? FAIL : SUCCESS;
        }

        return state;
    }

    private ExecutionState evaluateActions(IData<List<String>> data) {
        List<String> actions = Collections.emptyList();
        if (data != null && data.getResult() != null) {
            actions = data.getResult();
        }

        if (isActionEmpty(actions) ||
                Collections.indexOfSubList(actions, this.actions) > -1) {
            return SUCCESS;
        } else {
            return FAIL;
        }
    }

    private List<String> convertToActions(String actions) {
        return Stream.of(actions.split(",")).map(String::trim).collect(Collectors.toList());
    }

    private boolean isActionEmpty(List<String> actions) {
        return this.actions.size() == 1 &&
                this.actions.get(0).equals(KEY_EMPTY) &&
                actions.size() == 0;
    }

    @Override
    public IBehaviorCondition clone() {
        IBehaviorCondition clone = new ActionMatcher();
        clone.setConfigs(this.getConfigs());
        return clone;
    }
}