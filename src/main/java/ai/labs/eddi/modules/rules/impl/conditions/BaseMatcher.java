/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.SUCCESS;

/**
 * @author ginccc
 */
public abstract class BaseMatcher implements IRuleCondition {
    private static final String KEY_OCCURRENCE = "occurrence";
    static final String KEY_EMPTY = "empty";

    protected ConversationStepOccurrence occurrence = ConversationStepOccurrence.currentStep;

    private final String conversationOccurrenceQualifier = KEY_OCCURRENCE;

    enum ConversationStepOccurrence {
        currentStep, lastStep, anyStep, never
    }

    @Override
    public IRuleCondition clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException("Should be Overridden by Subclass!");
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put(conversationOccurrenceQualifier, occurrence.toString());

        return configs;
    }

    /**
     * Resolves the configured {@code occurrence}. A value that is not one of
     * {@link ConversationStepOccurrence} is rejected rather than silently falling
     * back to {@code currentStep} — that fallback turned a {@code lastStep} guard
     * (a typo such as {@code "lastSteps"}) into a globally firing rule.
     *
     * @throws IllegalArgumentException
     *             if the configured occurrence is unknown
     */
    void setConversationOccurrenceQualifier(Map<String, String> configs) {
        if (configs.containsKey(conversationOccurrenceQualifier)) {
            String conversationOccurrence = configs.get(conversationOccurrenceQualifier);
            if (conversationOccurrence != null) {
                try {
                    occurrence = ConversationStepOccurrence.valueOf(conversationOccurrence.trim());
                    return;
                } catch (IllegalArgumentException e) {
                    // fall through to the shared error message below
                }
            }

            throw new IllegalArgumentException(String.format("Unknown '%s' value '%s' — legal values are %s.", conversationOccurrenceQualifier,
                    conversationOccurrence, Arrays.toString(ConversationStepOccurrence.values())));
        }
    }

    boolean occurredInAnyStep(IConversationMemory memory, String dataKey, ValueEvaluation valueEvaluation) {
        List<IData<String>> allLatestData = memory.getAllSteps().getAllLatestData(dataKey);
        return allLatestData.stream().anyMatch(latestData -> valueEvaluation.evaluate(latestData) == SUCCESS);
    }

    public ConversationStepOccurrence getOccurrence() {
        return occurrence;
    }

    public void setOccurrence(ConversationStepOccurrence occurrence) {
        this.occurrence = occurrence;
    }

    @SuppressWarnings("rawtypes")
    interface ValueEvaluation {
        ExecutionState evaluate(IData data);
    }
}
