package ai.labs.eddi.engine.memory.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversationOutputTest {

    @Test
    void get_standardMapBehavior() {
        var output = new ConversationOutput();
        output.put("input", "Hello");
        assertEquals("Hello", output.get("input"));
    }

    @Test
    void get_typed_returnsTypedValue() {
        var output = new ConversationOutput();
        output.put("count", 42);
        Integer result = output.get("count", Integer.class);
        assertEquals(42, result);
    }

    @Test
    void get_typed_withString() {
        var output = new ConversationOutput();
        output.put("text", "hello");
        String result = output.get("text", String.class);
        assertEquals("hello", result);
    }

    @Test
    void get_typed_withList() {
        var output = new ConversationOutput();
        var list = List.of(Map.of("text", "Hi"));
        output.put("output", list);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = output.get("output", List.class);
        assertEquals(1, result.size());
    }

    @Test
    void get_typed_missingKey_returnsNull() {
        var output = new ConversationOutput();
        assertNull(output.get("missing", String.class));
    }

    @Test
    void extendsLinkedHashMap_preservesInsertionOrder() {
        var output = new ConversationOutput();
        output.put("first", 1);
        output.put("second", 2);
        output.put("third", 3);

        var keys = output.keySet().stream().toList();
        assertEquals("first", keys.get(0));
        assertEquals("second", keys.get(1));
        assertEquals("third", keys.get(2));
    }

    @Test
    void multipleEntries() {
        var output = new ConversationOutput();
        output.put("input", "How's the weather?");
        output.put("output", List.of(Map.of("text", "It's sunny!")));
        output.put("action", "weather_api");

        assertEquals(3, output.size());
        assertNotNull(output.get("output"));
    }
}
