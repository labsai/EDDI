package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MemoryKey} and the typed accessor methods on
 * {@link ConversationStep} and
 * {@link ConversationMemory.ConversationStepStack}.
 */
class MemoryKeyTest {

    private IConversationMemory.IWritableConversationStep step;

    @BeforeEach
    void setUp() {
        step = new ConversationStep(new ConversationOutput());
    }

    // ---- MemoryKey unit tests ----

    @Test
    void ofCreatesNonPublicKey() {
        MemoryKey<String> key = MemoryKey.of("test");
        assertEquals("test", key.key());
        assertFalse(key.isPublic());
    }

    @Test
    void ofPublicCreatesPublicKey() {
        MemoryKey<String> key = MemoryKey.ofPublic("test");
        assertEquals("test", key.key());
        assertTrue(key.isPublic());
    }

    @Test
    void equalityBasedOnKeyNameOnly() {
        MemoryKey<String> a = MemoryKey.of("x");
        MemoryKey<String> b = MemoryKey.ofPublic("x");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentKeysNotEqual() {
        MemoryKey<String> a = MemoryKey.of("a");
        MemoryKey<String> b = MemoryKey.of("b");
        assertNotEquals(a, b);
    }

    @Test
    void toStringContainsKeyName() {
        MemoryKey<String> key = MemoryKey.ofPublic("test");
        assertTrue(key.toString().contains("test"));
        assertTrue(key.toString().contains("public"));
    }

    // ---- Typed accessor integration tests on ConversationStep ----

    @Test
    void setAndGetRoundTrip() {
        MemoryKey<String> key = MemoryKey.of("greeting");
        step.set(key, "hello");

        String result = step.get(key);
        assertEquals("hello", result);
    }

    @Test
    void setPublicKeySetsIsPublicFlag() {
        MemoryKey<String> key = MemoryKey.ofPublic("visible");
        step.set(key, "data");

        IData<String> data = step.getData(key);
        assertNotNull(data);
        assertTrue(data.isPublic());
        assertEquals("data", data.getResult());
    }

    @Test
    void setPrivateKeySetsIsPublicFalse() {
        MemoryKey<String> key = MemoryKey.of("hidden");
        step.set(key, "secret");

        IData<String> data = step.getData(key);
        assertNotNull(data);
        assertFalse(data.isPublic());
    }

    @Test
    void getReturnsNullForMissingKey() {
        MemoryKey<String> key = MemoryKey.of("missing");
        assertNull(step.get(key));
    }

    @Test
    void getDataReturnsNullForMissingKey() {
        MemoryKey<String> key = MemoryKey.of("missing");
        assertNull(step.getData(key));
    }

    @Test
    void typedGetDataReturnsFullWrapper() {
        MemoryKey<List<String>> key = MemoryKey.ofPublic("actions");
        List<String> actions = List.of("greet", "farewell");
        step.set(key, actions);

        IData<List<String>> data = step.getData(key);
        assertNotNull(data);
        assertEquals(actions, data.getResult());
        assertEquals("actions", data.getKey());
        assertTrue(data.isPublic());
    }

    @Test
    void getLatestDataWithMemoryKey() {
        MemoryKey<String> key = MemoryKey.of("expressions:parsed");
        step.set(key, "hello(greeting)");

        IData<String> result = step.getLatestData(key);
        assertNotNull(result);
        assertEquals("hello(greeting)", result.getResult());
    }

    @Test
    void getLatestDataWithMemoryKeyPrefixMatch() {
        // Store data with a longer key that starts with the MemoryKey's key
        step.storeData(new Data<>("expressions:parsed:extra", "extended"));
        MemoryKey<String> key = MemoryKey.of("expressions:parsed");

        IData<String> result = step.getLatestData(key);
        assertNotNull(result);
        assertEquals("extended", result.getResult());
    }

    // ---- ConversationStepStack typed accessor ----

    @Test
    void stepStackGetLatestDataWithMemoryKey() {
        var memory = new ConversationMemory("bot1", 1);
        MemoryKey<List<String>> key = MemoryKey.ofPublic("actions");

        // Store data in first step
        memory.getCurrentStep().set(key, List.of("action1"));

        // Start a new step
        memory.startNextStep();

        // The previous steps should find the data
        IData<List<String>> result = memory.getPreviousSteps().getLatestData(key);
        assertNotNull(result);
        assertEquals(List.of("action1"), result.getResult());
    }

    // ---- MemoryKeys registry tests ----

    @Test
    void memoryKeysActionsIsPublic() {
        assertTrue(MemoryKeys.ACTIONS.isPublic());
        assertEquals("actions", MemoryKeys.ACTIONS.key());
    }

    @Test
    void memoryKeysInputInitialIsNotPublic() {
        assertFalse(MemoryKeys.INPUT_INITIAL.isPublic());
        assertEquals("input:initial", MemoryKeys.INPUT_INITIAL.key());
    }

    @Test
    void memoryKeysInputIsPublic() {
        assertTrue(MemoryKeys.INPUT.isPublic());
        assertEquals("input", MemoryKeys.INPUT.key());
    }

    @Test
    void memoryKeysExpressionsIsNotPublic() {
        assertFalse(MemoryKeys.EXPRESSIONS_PARSED.isPublic());
        assertEquals("expressions:parsed", MemoryKeys.EXPRESSIONS_PARSED.key());
    }
}
