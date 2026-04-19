package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MemoryItemConverterTest {

    private MemoryItemConverter converter;

    @BeforeEach
    void setUp() {
        converter = new MemoryItemConverter();
    }

    @Test
    void convert_withBasicMemory_containsAllTopLevelKeys() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        Map<String, Object> result = converter.convert(memory);

        assertNotNull(result);
        assertTrue(result.containsKey("conversationLog"));
        assertTrue(result.containsKey("userInfo"));
        assertTrue(result.containsKey("conversationInfo"));
    }

    @Test
    void convert_userInfo_containsUserId() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        Map<String, Object> result = converter.convert(memory);

        @SuppressWarnings("unchecked")
        var userInfo = (Map<String, Object>) result.get("userInfo");
        assertNotNull(userInfo);
        assertEquals("user-1", userInfo.get("userId"));
    }

    @Test
    void convert_conversationInfo_containsAgentIdAndVersion() {
        var memory = new ConversationMemory("conv-1", "agent-1", 2, "user-1");
        Map<String, Object> result = converter.convert(memory);

        @SuppressWarnings("unchecked")
        var convInfo = (Map<String, Object>) result.get("conversationInfo");
        assertNotNull(convInfo);
        assertEquals("conv-1", convInfo.get("conversationId"));
        assertEquals("agent-1", convInfo.get("agentId"));
        assertEquals("2", convInfo.get("agentVersion"));
    }

    @Test
    void convert_withContext_containsContextMap() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        var ctx = new Context();
        ctx.setType(Context.ContextType.string);
        ctx.setValue("en");
        memory.getCurrentStep().storeData(new Data<>("context:lang", ctx));

        Map<String, Object> result = converter.convert(memory);

        assertTrue(result.containsKey("context"));
        @SuppressWarnings("unchecked")
        var contextMap = (Map<String, Object>) result.get("context");
        assertEquals("en", contextMap.get("lang"));
    }

    @Test
    void convert_memorySection_containsCurrentLastPast() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        // Add some data to make memory non-empty
        memory.getCurrentStep().addConversationOutputString("input", "hello");

        Map<String, Object> result = converter.convert(memory);

        assertTrue(result.containsKey("memory"));
        @SuppressWarnings("unchecked")
        var memoryMap = (Map<String, Object>) result.get("memory");
        assertTrue(memoryMap.containsKey("current"));
        assertTrue(memoryMap.containsKey("last"));
        assertTrue(memoryMap.containsKey("past"));
    }

    @Test
    void convert_withProperties_containsPropertiesMap() {
        var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
        var prop = new ai.labs.eddi.configs.properties.model.Property("name", "John",
                ai.labs.eddi.configs.properties.model.Property.Scope.conversation);
        memory.getConversationProperties().put("name", prop);

        Map<String, Object> result = converter.convert(memory);

        assertTrue(result.containsKey("properties"));
    }
}
