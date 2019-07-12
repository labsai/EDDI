package ai.labs.behavior.impl.conditions;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
class BaseMatcherTest {
    static final String KEY_OCCURRENCE = "occurrence";
    BaseMatcher matcher;

    @Test
    void setValues_currentStep() {
        //setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.currentStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        //test
        matcher.setConfigs(values);

        //assert
        Assertions.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    void setValues_lastStep() {
        //setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.lastStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        //test
        matcher.setConfigs(values);

        //assert
        Assertions.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    void setValues_anyStep() {
        //setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.anyStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        //test
        matcher.setConfigs(values);

        //assert
        Assertions.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    void setValues_never() {
        //setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.never;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        //test
        matcher.setConfigs(values);

        //assert
        Assertions.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }
}
