/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
abstract class BaseMatcherTest {
    static final String KEY_OCCURRENCE = "occurrence";
    BaseMatcher matcher;

    @Test
    public void setValues_currentStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.currentStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        // test
        matcher.setConfigs(values);

        // assert
        Assertions.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    public void setValues_lastStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.lastStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        // test
        matcher.setConfigs(values);

        // assert
        Assertions.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    public void setValues_anyStep() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.anyStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        // test
        matcher.setConfigs(values);

        // assert
        Assertions.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    public void setValues_never() throws Exception {
        // setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.never;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        // test
        matcher.setConfigs(values);

        // assert
        Assertions.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    /**
     * A typo'd occurrence used to silently degrade to currentStep, which turns a
     * lastStep guard into a globally firing rule.
     */
    @Test
    public void setValues_unknownOccurrence_isRejected() {
        // setup
        Map<String, String> values = new HashMap<>();
        values.put(KEY_OCCURRENCE, "lastSteps");

        // test
        IllegalArgumentException exception = Assertions.assertThrows(IllegalArgumentException.class, () -> matcher.setConfigs(values));

        // assert
        Assertions.assertTrue(exception.getMessage().contains("lastSteps"), exception.getMessage());
        Assertions.assertTrue(exception.getMessage().contains(BaseMatcher.ConversationStepOccurrence.lastStep.name()), exception.getMessage());
        Assertions.assertTrue(exception.getMessage().contains(BaseMatcher.ConversationStepOccurrence.anyStep.name()), exception.getMessage());
    }
}
