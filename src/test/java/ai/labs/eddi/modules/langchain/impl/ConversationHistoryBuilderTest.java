package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationLog;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.memory.model.ConversationLog.ConversationPart.ContentType.text;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationHistoryBuilderTest {

    private ConversationHistoryBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new ConversationHistoryBuilder();
    }

    // ==================== convertMessage Tests ====================

    @Nested
    @DisplayName("convertMessage")
    class ConvertMessageTests {

        @Test
        @DisplayName("should convert user role to UserMessage")
        void convertMessage_user() {
            var content = new ConversationLog.ConversationPart.Content(text, "Hello");
            var part = new ConversationLog.ConversationPart("user", List.of(content));

            ChatMessage result = builder.convertMessage(part);

            assertInstanceOf(UserMessage.class, result);
        }

        @Test
        @DisplayName("should convert assistant role to AiMessage")
        void convertMessage_assistant() {
            var content = new ConversationLog.ConversationPart.Content(text, "Hello back");
            var part = new ConversationLog.ConversationPart("assistant", List.of(content));

            ChatMessage result = builder.convertMessage(part);

            assertInstanceOf(AiMessage.class, result);
            assertEquals("Hello back", ((AiMessage) result).text());
        }

        @Test
        @DisplayName("should convert unknown role to SystemMessage")
        void convertMessage_system() {
            var content = new ConversationLog.ConversationPart.Content(text, "System info");
            var part = new ConversationLog.ConversationPart("system", List.of(content));

            ChatMessage result = builder.convertMessage(part);

            assertInstanceOf(SystemMessage.class, result);
            assertEquals("System info", ((SystemMessage) result).text());
        }

        @Test
        @DisplayName("should join multiple text contents for assistant")
        void convertMessage_multipleContents_joined() {
            var content1 = new ConversationLog.ConversationPart.Content(text, "Part 1");
            var content2 = new ConversationLog.ConversationPart.Content(text, "Part 2");
            var part = new ConversationLog.ConversationPart("assistant", List.of(content1, content2));

            ChatMessage result = builder.convertMessage(part);

            assertInstanceOf(AiMessage.class, result);
            assertEquals("Part 1 Part 2", ((AiMessage) result).text());
        }
    }

    // ==================== buildMessages Tests ====================

    @Nested
    @DisplayName("buildMessages")
    class BuildMessagesTests {

        @Test
        @DisplayName("should prepend system message when provided")
        void buildMessages_withSystemMessage() {
            IConversationMemory memory = mock(IConversationMemory.class);

            // Setup minimal conversation output
            var output = new ConversationOutput();
            output.put("input", "Hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(
                    memory, "You are helpful", null, -1, true);

            assertFalse(messages.isEmpty());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
            assertEquals("You are helpful", ((SystemMessage) messages.getFirst()).text());
        }

        @Test
        @DisplayName("should not prepend system message when null")
        void buildMessages_noSystemMessage() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(
                    memory, null, null, -1, true);

            assertFalse(messages.isEmpty());
            // First message should NOT be SystemMessage
            assertInstanceOf(UserMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("should not prepend system message when empty string")
        void buildMessages_emptySystemMessage() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Hi");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(
                    memory, "", null, -1, true);

            assertFalse(messages.isEmpty());
            assertInstanceOf(UserMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("should replace last user message with prompt when provided")
        void buildMessages_withPrompt() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output = new ConversationOutput();
            output.put("input", "Original user message");
            when(memory.getConversationOutputs()).thenReturn(List.of(output));

            List<ChatMessage> messages = builder.buildMessages(
                    memory, null, "Custom prompt", -1, true);

            // The last message should be the custom prompt, not the original input
            var lastMessage = messages.getLast();
            assertInstanceOf(UserMessage.class, lastMessage);
            // Verify it contains the custom prompt text
            assertTrue(((UserMessage) lastMessage).singleText().contains("Custom prompt"));
        }

        @Test
        @DisplayName("should handle empty conversation history")
        void buildMessages_emptyHistory() {
            IConversationMemory memory = mock(IConversationMemory.class);
            when(memory.getConversationOutputs()).thenReturn(new ArrayList<>());

            List<ChatMessage> messages = builder.buildMessages(
                    memory, "System", null, -1, true);

            // Should have just the system message
            assertEquals(1, messages.size());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("should respect logSizeLimit=0 (no history)")
        void buildMessages_logSizeLimitZero() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var output1 = new ConversationOutput();
            output1.put("input", "Hi");
            var output2 = new ConversationOutput();
            output2.put("input", "How are you?");
            when(memory.getConversationOutputs()).thenReturn(List.of(output1, output2));

            List<ChatMessage> messages = builder.buildMessages(
                    memory, "System", null, 0, true);

            // logSize=0 means no history entries, only system message
            assertEquals(1, messages.size());
            assertInstanceOf(SystemMessage.class, messages.getFirst());
        }

        @Test
        @DisplayName("should limit history with logSizeLimit > 0")
        void buildMessages_withLogSizeLimit() {
            IConversationMemory memory = mock(IConversationMemory.class);

            var outputs = new ArrayList<ConversationOutput>();
            for (int i = 0; i < 5; i++) {
                var output = new ConversationOutput();
                output.put("input", "Message " + i);
                outputs.add(output);
            }
            when(memory.getConversationOutputs()).thenReturn(outputs);

            List<ChatMessage> messages = builder.buildMessages(
                    memory, null, null, 2, true);

            // With logSizeLimit=2, should only have last 2 conversation entries
            // Each entry could produce 1-2 messages (user + optional assistant)
            assertTrue(messages.size() <= 4, "Should have at most 4 messages (2 entries × 2)");
            assertTrue(messages.size() >= 2, "Should have at least 2 messages (2 user inputs)");
        }
    }
}
