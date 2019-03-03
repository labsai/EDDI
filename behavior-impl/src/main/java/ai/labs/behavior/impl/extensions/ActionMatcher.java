package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.utilities.StringUtilities;
import lombok.Getter;
import lombok.Setter;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ai.labs.behavior.impl.extensions.IBehaviorExtension.ExecutionState.FAIL;
import static ai.labs.behavior.impl.extensions.IBehaviorExtension.ExecutionState.SUCCESS;

/**
 * @author ginccc
 */
public class ActionMatcher extends BaseMatcher implements IBehaviorExtension {
    private static final String ID = "actionmatcher";
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
    public Map<String, String> getValues() {
        Map<String, String> result = new HashMap<>();
        result.put(actionsQualifier, StringUtilities.joinStrings(",", actions));
        result.putAll(super.getValues());

        return result;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(actionsQualifier)) {
                actions = convertToActions(values.get(actionsQualifier));
            }

            setConversationOccurrenceQualifier(values);
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        IData<List<String>> data;
        switch (occurrence) {
            case currentStep:
                data = memory.getCurrentStep().getLatestData(KEY_ACTIONS);
                state = evaluateActions(data);
                break;
            case lastStep:
                IConversationMemory.IConversationStepStack previousSteps = memory.getPreviousSteps();
                if (previousSteps.size() > 0) {
                    data = previousSteps.get(0).getLatestData(KEY_ACTIONS);
                    state = evaluateActions(data);
                } else {
                    state = FAIL;
                }
                break;
            case anyStep:
                state = occurredInAnyStep(memory, KEY_ACTIONS, this::evaluateActions) ? SUCCESS : FAIL;
                break;
            case never:
                state = occurredInAnyStep(memory, KEY_ACTIONS, this::evaluateActions) ? FAIL : SUCCESS;
                break;
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
    public IBehaviorExtension clone() {
        IBehaviorExtension clone = new ActionMatcher();
        clone.setValues(getValues());
        return clone;
    }
}