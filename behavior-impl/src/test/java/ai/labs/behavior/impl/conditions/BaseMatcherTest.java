package ai.labs.behavior.impl.conditions;

import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@Ignore
class BaseMatcherTest {
    static final String KEY_OCCURRENCE = "occurrence";
    BaseMatcher matcher;

    @Test
    public void setValues_currentStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.currentStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        //test
        matcher.setConfigs(values);

        //assert
        Assert.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    public void setValues_lastStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.lastStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        //test
        matcher.setConfigs(values);

        //assert
        Assert.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    public void setValues_anyStep() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.anyStep;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        //test
        matcher.setConfigs(values);

        //assert
        Assert.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }

    @Test
    public void setValues_never() throws Exception {
        //setup
        Map<String, String> values = new HashMap<>();
        BaseMatcher.ConversationStepOccurrence expectedOccurrence = BaseMatcher.ConversationStepOccurrence.never;
        values.put(KEY_OCCURRENCE, expectedOccurrence.toString());

        //test
        matcher.setConfigs(values);

        //assert
        Assert.assertEquals(expectedOccurrence, matcher.getOccurrence());
    }
}
