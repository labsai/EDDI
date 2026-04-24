/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Attachment;
import dev.langchain4j.data.message.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link MultimodalMessageEnhancer}.
 */
class MultimodalMessageEnhancerTest {

    private IConversationMemory memory;
    private IWritableConversationStep currentStep;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
        currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
    }

    // ==================== No-Op Cases ====================

    @Nested
    class NoOpCases {

        @Test
        void shouldDoNothingWhenMessagesNull() {
            MultimodalMessageEnhancer.enhanceLastUserMessage(null, memory);
            // No exception = pass
        }

        @Test
        void shouldDoNothingWhenMessagesEmpty() {
            List<ChatMessage> messages = new ArrayList<>();
            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);
            assertTrue(messages.isEmpty());
        }

        @Test
        void shouldDoNothingWhenNoAttachments() {
            when(currentStep.getLatestData(ATTACHMENTS)).thenReturn(null);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Hello"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            assertEquals(1, messages.size());
            assertInstanceOf(UserMessage.class, messages.get(0));
        }

        @Test
        void shouldDoNothingWhenAttachmentDataEmpty() {
            @SuppressWarnings("unchecked")
            IData<List<?>> data = mock(IData.class);
            when(data.getResult()).thenReturn(List.of());
            when(currentStep.getLatestData(ATTACHMENTS)).thenReturn(data);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Hello"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            assertEquals(1, messages.size());
        }

        @Test
        void shouldDoNothingWhenNoUserMessage() {
            Attachment att = new Attachment();
            att.setMimeType("image/png");
            att.setUrl("https://example.com/img.png");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage("System"));
            messages.add(AiMessage.from("Response"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            // Messages unchanged
            assertEquals(2, messages.size());
            assertInstanceOf(SystemMessage.class, messages.get(0));
            assertInstanceOf(AiMessage.class, messages.get(1));
        }
    }

    // ==================== Image Attachments ====================

    @Nested
    class ImageAttachments {

        @Test
        void shouldEnhanceWithUrlImage() {
            Attachment att = new Attachment();
            att.setMimeType("image/png");
            att.setUrl("https://example.com/photo.png");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(new SystemMessage("system"));
            messages.add(UserMessage.from("Describe this image"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            assertEquals(2, messages.size());
            UserMessage enhanced = (UserMessage) messages.get(1);
            // Should have original text + image content
            assertEquals(2, enhanced.contents().size());
            assertInstanceOf(TextContent.class, enhanced.contents().get(0));
            assertInstanceOf(ImageContent.class, enhanced.contents().get(1));
        }

        @Test
        void shouldEnhanceWithBase64Image() {
            Attachment att = new Attachment();
            att.setMimeType("image/jpeg");
            att.setBase64Data("iVBORw0KGgo=");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("What is this?"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            assertInstanceOf(TextContent.class, enhanced.contents().get(0));
            assertInstanceOf(ImageContent.class, enhanced.contents().get(1));
        }

        @Test
        void shouldEnhanceWithMultipleImages() {
            Attachment att1 = new Attachment();
            att1.setMimeType("image/png");
            att1.setUrl("https://example.com/1.png");

            Attachment att2 = new Attachment();
            att2.setMimeType("image/jpeg");
            att2.setUrl("https://example.com/2.jpg");

            mockAttachments(att1, att2);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Compare these"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            UserMessage enhanced = (UserMessage) messages.get(0);
            // 1 text + 2 images
            assertEquals(3, enhanced.contents().size());
        }
    }

    // ==================== Non-Image Attachments ====================

    @Nested
    class NonImageAttachments {

        @Test
        void shouldAddMetadataTextForNonImageTypes() {
            Attachment att = new Attachment();
            att.setMimeType("application/pdf");
            att.setFileName("report.pdf");
            att.setSizeBytes(15240);
            att.setUrl("https://example.com/report.pdf");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Summarize this"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            TextContent metadata = (TextContent) enhanced.contents().get(1);
            assertTrue(metadata.text().contains("report.pdf"));
            assertTrue(metadata.text().contains("application/pdf"));
        }

        @Test
        void shouldHandleAttachmentWithNoContentSource() {
            Attachment att = new Attachment(); // no url, no base64, no storageRef
            att.setMimeType("image/png");
            mockAttachments(att);

            List<ChatMessage> messages = new ArrayList<>();
            messages.add(UserMessage.from("Hello"));

            MultimodalMessageEnhancer.enhanceLastUserMessage(messages, memory);

            // NONE content source → null → not added
            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(1, enhanced.contents().size()); // only original text
        }
    }

    // ==================== Helpers ====================

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockAttachments(Attachment... attachments) {
        IData data = mock(IData.class);
        when(data.getResult()).thenReturn(List.of(attachments));
        when(currentStep.getLatestData(ATTACHMENTS)).thenReturn(data);
    }
}
