package ai.labs.eddi.engine.model;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContextTest {

    @Test
    void defaultConstructor_fieldsNull() {
        var ctx = new Context();
        assertNull(ctx.getType());
        assertNull(ctx.getValue());
    }

    @Test
    void parameterizedConstructor_setsFields() {
        var ctx = new Context(Context.ContextType.string, "hello");
        assertEquals(Context.ContextType.string, ctx.getType());
        assertEquals("hello", ctx.getValue());
    }

    @Test
    void setType_updatesType() {
        var ctx = new Context();
        ctx.setType(Context.ContextType.object);
        assertEquals(Context.ContextType.object, ctx.getType());
    }

    @Test
    void setValue_updatesValue() {
        var ctx = new Context();
        ctx.setValue(Map.of("key", "val"));
        assertNotNull(ctx.getValue());
    }

    @Test
    void contextType_string() {
        var ctx = new Context(Context.ContextType.string, "text");
        assertEquals("text", ctx.getValue());
    }

    @Test
    void contextType_expressions() {
        var ctx = new Context(Context.ContextType.expressions, "intent(greeting)");
        assertEquals(Context.ContextType.expressions, ctx.getType());
    }

    @Test
    void contextType_object() {
        var payload = Map.of("name", "John");
        var ctx = new Context(Context.ContextType.object, payload);
        assertEquals(payload, ctx.getValue());
    }

    @Test
    void contextType_array() {
        var list = List.of("a", "b", "c");
        var ctx = new Context(Context.ContextType.array, list);
        assertEquals(list, ctx.getValue());
    }

    @Test
    void allContextTypesExist() {
        assertEquals(4, Context.ContextType.values().length);
    }
}
