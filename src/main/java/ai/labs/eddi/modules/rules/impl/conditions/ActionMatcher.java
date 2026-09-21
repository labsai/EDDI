/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.utils.StringUtilities;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.*;

/**
 * @author ginccc
 */
public class ActionMatcher extends BaseMatcher {
    public static final String ID = "actionmatcher";
    private static final String KEY_ACTIONS = "actions";

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
            // containsKey() is also true for an explicit JSON null ("actions": null), so
            // testing the VALUE rather than the key matters: passing null on to
            // convertToActions would throw an opaque NullPointerException here and rob
            // validateConfiguration() of the chance to report the real problem with a
            // message naming the rule. Leaving the field at its empty default routes a
            // null or blank value into exactly that check. Mirrors InputMatcher.
            String configuredActions = configs.get(actionsQualifier);
            if (configuredActions != null && !configuredActions.isBlank()) {
                actions = convertToActions(configuredActions);
            }

            setConversationOccurrenceQualifier(configs);
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) {
        IData<List<String>> data;
        ExecutionState state = NOT_EXECUTED;
        switch (occurrence) {
            case currentStep -> {
                data = memory.getCurrentStep().getLatestData(ACTIONS);
                state = evaluateActions(data);
            }
            case lastStep -> {
                IConversationMemory.IConversationStepStack previousSteps = memory.getPreviousSteps();
                if (previousSteps.size() > 0) {
                    data = previousSteps.get(0).getLatestData(ACTIONS);
                    state = evaluateActions(data);
                } else {
                    state = FAIL;
                }
            }
            case anyStep -> state = occurredInAnyStep(memory, ACTIONS.key(), this::evaluateActions) ? SUCCESS : FAIL;
            case never -> state = occurredInAnyStep(memory, ACTIONS.key(), this::evaluateActions) ? FAIL : SUCCESS;
            default -> {
            }
        }

        return state;
    }

    private ExecutionState evaluateActions(IData<List<String>> data) {
        List<String> actions = Collections.emptyList();
        if (data != null && data.getResult() != null) {
            actions = data.getResult();
        }

        if (isActionEmpty(actions) || Collections.indexOfSubList(actions, this.actions) > -1) {
            return SUCCESS;
        } else {
            return FAIL;
        }
    }

    @Override
    public void validateConfiguration() {
        if (actions.isEmpty()) {
            throw new IllegalArgumentException(String.format(
                    "'%s' requires a non-empty '%s' config value — an empty matcher would match every conversation step.", ID,
                    actionsQualifier));
        }
    }

    private List<String> convertToActions(String actions) {
        return Stream.of(actions.split(",")).map(String::trim).filter(action -> !action.isEmpty()).toList();
    }

    private boolean isActionEmpty(List<String> actions) {
        return this.actions.size() == 1 && this.actions.get(0).equals(KEY_EMPTY) && actions.size() == 0;
    }

    @Override
    public IRuleCondition clone() {
        IRuleCondition clone = new ActionMatcher();
        clone.setConfigs(this.getConfigs());
        return clone;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }
}