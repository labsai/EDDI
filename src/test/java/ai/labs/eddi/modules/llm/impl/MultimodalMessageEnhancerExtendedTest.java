package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Attachment;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Extended tests for {@link MultimodalMessageEnhancer} — covers STORED path,
 * invalid URL handling, null mimeType, null attachment result, and
 * non-Attachment objects.
 */
class MultimodalMessageEnhancerExtendedTest {

    private IConversationMemory memory;
    private IWritableConversationStep currentStep;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
        currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
    }

    @Nested
    @DisplayName("STORED content source")
    class StoredContentSource {

        @Test
        @DisplayName("should produce text description for STORED image attachment")
        void storedImageProducesTextFallback() {
            Attachment att = new Attachment();
            att.setMimeType("image/png");
            att.setFileName("stored-img.png");
            att.setStorageRef("store://abc-123");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("What is this?"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            TextContent fallback = (TextContent) enhanced.contents().get(1);
            assertTrue(fallback.text().contains("stored-img.png"));
            assertTrue(fallback.text().contains("not yet implemented"));
        }

        @Test
        @DisplayName("should handle null fileName for STORED attachment")
        void storedWithNullFileName() {
            Attachment att = new Attachment();
            att.setMimeType("image/jpeg");
            att.setFileName(null);
            att.setStorageRef("store://xyz");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Describe"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            TextContent fallback = (TextContent) enhanced.contents().get(1);
            assertTrue(fallback.text().contains("unnamed"));
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("should handle invalid URL gracefully")
        void invalidUrlReturnsNull() {
            Attachment att = new Attachment();
            att.setMimeType("image/png");
            att.setUrl("not a valid url !!!");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Describe"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            // Invalid URL → null → not added → original text only
            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(1, enhanced.contents().size());
        }

        @Test
        @DisplayName("should skip attachment with null mimeType")
        void nullMimeTypeSkipped() {
            Attachment att = new Attachment();
            att.setMimeType(null);
            att.setUrl("https://example.com/img.png");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Hello"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(1, enhanced.contents().size());
        }

        @Test
        @DisplayName("should handle null result in attachment data")
        void nullResultInAttachmentData() {
            @SuppressWarnings("unchecked")
            IData<List<?>> data = mock(IData.class);
            when(data.getResult()).thenReturn(null);
            when(currentStep.getLatestData(ATTACHMENTS)).thenReturn(data);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Hello"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            assertEquals(1, messages.size());
        }
    }

    @Nested
    @DisplayName("Non-Attachment objects in list")
    class NonAttachmentObjects {

        @Test
        @DisplayName("should skip non-Attachment objects in raw attachments list")
        void skipsNonAttachmentObjects() {
            @SuppressWarnings({"unchecked", "rawtypes"})
            IData data = mock(IData.class);
            List<Object> mixed = new ArrayList<>();
            mixed.add("just a string");
            mixed.add(42);
            when(data.getResult()).thenReturn(mixed);
            when(currentStep.getLatestData(ATTACHMENTS)).thenReturn(data);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Hello"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            // No valid Attachment → message unchanged
            UserMessage msg = (UserMessage) messages.get(0);
            assertEquals(1, msg.contents().size());
        }
    }

    @Nested
    @DisplayName("Non-image attachment with null fileName")
    class NonImageNullFileName {

        @Test
        @DisplayName("should use 'unnamed' for non-image attachment with null fileName")
        void usesUnnamedFallback() {
            Attachment att = new Attachment();
            att.setMimeType("application/octet-stream");
            att.setFileName(null);
            att.setSizeBytes(1024);
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Process"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            TextContent meta = (TextContent) enhanced.contents().get(1);
            assertTrue(meta.text().contains("unnamed"));
            assertTrue(meta.text().contains("1024 bytes"));
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockAttachments(Attachment... attachments) {
        IData data = mock(IData.class);
        when(data.getResult()).thenReturn(List.of(attachments));
        when(currentStep.getLatestData(ATTACHMENTS)).thenReturn(data);
    }
}
