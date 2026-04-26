/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryItemConverter")
class MemoryItemConverterTest {

    private MemoryItemConverter converter;

    @BeforeEach
    void setUp() {
        converter = new MemoryItemConverter();
    }

    // ─── Top-Level Structure ────────────────────────────────────

    @Nested
    @DisplayName("Top-level output structure")
    class TopLevelStructure {

        @Test
        @DisplayName("contains all required top-level keys with valid identity")
        void containsAllTopLevelKeys() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            Map<String, Object> result = converter.convert(memory);

            assertNotNull(result);
            assertTrue(result.containsKey("conversationLog"));
            assertTrue(result.containsKey("userInfo"));
            assertTrue(result.containsKey("conversationInfo"));
        }

        @Test
        @DisplayName("conversationLog is always present even with empty memory")
        void conversationLogAlwaysPresent() {
            var memory = new ConversationMemory("agent-1", 1);
            Map<String, Object> result = converter.convert(memory);
            assertTrue(result.containsKey("conversationLog"));
        }
    }

    // ─── Context ────────────────────────────────────────────────

    @Nested
    @DisplayName("Context handling")
    class ContextTests {

        @Test
        @DisplayName("with context data — produces context map and top-level merge")
        void withContextData() {
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
            // Context entries are also merged into top level
            assertEquals("en", result.get("lang"));
        }

        @Test
        @DisplayName("empty context list — no context key present")
        void emptyContextList() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            Map<String, Object> result = converter.convert(memory);
            assertFalse(result.containsKey("context"));
        }

        @Test
        @DisplayName("multiple context entries — all present in map")
        void multipleContextEntries() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");

            var ctx1 = new Context();
            ctx1.setType(Context.ContextType.string);
            ctx1.setValue("en");
            memory.getCurrentStep().storeData(new Data<>("context:language", ctx1));

            var ctx2 = new Context();
            ctx2.setType(Context.ContextType.string);
            ctx2.setValue("premium");
            memory.getCurrentStep().storeData(new Data<>("context:tier", ctx2));

            Map<String, Object> result = converter.convert(memory);

            @SuppressWarnings("unchecked")
            var contextMap = (Map<String, Object>) result.get("context");
            assertEquals("en", contextMap.get("language"));
            assertEquals("premium", contextMap.get("tier"));
        }
    }

    // ─── Properties ─────────────────────────────────────────────

    @Nested
    @DisplayName("Properties handling")
    class PropertiesTests {

        @Test
        @DisplayName("with properties — produces properties map")
        void withProperties() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            var prop = new Property("name", "John", Property.Scope.conversation);
            memory.getConversationProperties().put("name", prop);

            Map<String, Object> result = converter.convert(memory);
            assertTrue(result.containsKey("properties"));
        }

        @Test
        @DisplayName("empty properties — no properties key present")
        void emptyProperties() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            Map<String, Object> result = converter.convert(memory);
            assertFalse(result.containsKey("properties"));
        }
    }

    // ─── User/Conversation Info ─────────────────────────────────

    @Nested
    @DisplayName("Info objects")
    class InfoObjectTests {

        @Test
        @DisplayName("userInfo contains userId")
        void userInfoContainsUserId() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            Map<String, Object> result = converter.convert(memory);

            @SuppressWarnings("unchecked")
            var userInfo = (Map<String, Object>) result.get("userInfo");
            assertNotNull(userInfo);
            assertEquals("user-1", userInfo.get("userId"));
        }

        @Test
        @DisplayName("conversationInfo contains conversationId, agentId, agentVersion")
        void conversationInfoAllFields() {
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
        @DisplayName("null userId — no userInfo key present")
        void nullUserId() {
            var memory = new ConversationMemory("agent-1", 1);
            Map<String, Object> result = converter.convert(memory);
            assertFalse(result.containsKey("userInfo"));
        }

        @Test
        @DisplayName("conversationInfo merges multiple fields into same map")
        void conversationInfoMergesFields() {
            var memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
            Map<String, Object> result = converter.convert(memory);

            @SuppressWarnings("unchecked")
            var convInfo = (Map<String, Object>) result.get("conversationInfo");
            // All three fields should be in the same map
            assertEquals(3, convInfo.size());
        }
    }

    // ─── Memory Section ─────────────────────────────────────────

    @Nested
    @DisplayName("Memory section (current/last/past)")
    class MemorySectionTests {

        @Test
        @DisplayName("memory section with data — contains current, last, past")
        void memoryWithData() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
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
        @DisplayName("no previous steps — last is empty, past is empty list")
        void noPreviousSteps() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            memory.getCurrentStep().addConversationOutputString("input", "hello");

            Map<String, Object> result = converter.convert(memory);

            @SuppressWarnings("unchecked")
            var memoryMap = (Map<String, Object>) result.get("memory");

            var last = (ConversationOutput) memoryMap.get("last");
            assertTrue(last.isEmpty());

            @SuppressWarnings("unchecked")
            var past = (List<?>) memoryMap.get("past");
            assertTrue(past.isEmpty());
        }

        @Test
        @DisplayName("with previous steps — last contains previous step output, past contains earlier")
        void withPreviousSteps() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            memory.getCurrentStep().addConversationOutputString("input", "first");
            memory.startNextStep();
            memory.getCurrentStep().addConversationOutputString("input", "second");
            memory.startNextStep();
            memory.getCurrentStep().addConversationOutputString("input", "third");

            Map<String, Object> result = converter.convert(memory);

            @SuppressWarnings("unchecked")
            var memoryMap = (Map<String, Object>) result.get("memory");

            // Current should be "third"
            var current = (ConversationOutput) memoryMap.get("current");
            assertEquals("third", current.get("input"));

            // Last should be "second" (previous step index 0 = most recent)
            var last = (ConversationOutput) memoryMap.get("last");
            assertEquals("second", last.get("input"));

            // Past should contain only "first" (all outputs except first one, which is
            // current)
            @SuppressWarnings("unchecked")
            var past = (List<ConversationOutput>) memoryMap.get("past");
            assertFalse(past.isEmpty());
        }

        @Test
        @DisplayName("single output — past is empty list")
        void singleOutputPastEmpty() {
            var memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            memory.getCurrentStep().addConversationOutputString("input", "only");

            Map<String, Object> result = converter.convert(memory);

            @SuppressWarnings("unchecked")
            var memoryMap = (Map<String, Object>) result.get("memory");

            @SuppressWarnings("unchecked")
            var past = (List<?>) memoryMap.get("past");
            assertTrue(past.isEmpty());
        }
    }
}
