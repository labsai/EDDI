/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IAttachmentStore;
import ai.labs.eddi.engine.memory.IAttachmentStore.Attachment;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("AttachmentForwarder Tests")
class AttachmentForwarderTest {

    private AttachmentForwarder forwarder;
    private IAttachmentStore attachmentStore;

    @BeforeEach
    void setUp() {
        forwarder = new AttachmentForwarder();
        attachmentStore = mock(IAttachmentStore.class);
    }

    @Nested
    @DisplayName("Empty/Null Input")
    class EmptyInputTests {

        @Test
        @DisplayName("Should return empty for null attachments")
        void testNullAttachments() {
            List<Content> result = forwarder.toContent(null, attachmentStore, "conv-1");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty for empty list")
        void testEmptyList() {
            List<Content> result = forwarder.toContent(List.of(), attachmentStore, "conv-1");
            assertTrue(result.isEmpty());
        }
    }

    @Nested
    @DisplayName("Image Attachments")
    class ImageTests {

        @Test
        @DisplayName("Should convert JPEG to ImageContent")
        void testJpegToImageContent() throws IAttachmentStore.AttachmentStoreException {
            byte[] data = new byte[]{1, 2, 3, 4, 5};
            Attachment attachment = new Attachment("ref-1", "photo.jpg", "image/jpeg", 5, "conv-1");
            when(attachmentStore.load("ref-1", "conv-1")).thenReturn(data);

            List<Content> result = forwarder.toContent(List.of(attachment), attachmentStore, "conv-1");

            assertEquals(1, result.size());
            assertInstanceOf(ImageContent.class, result.get(0));
        }

        @Test
        @DisplayName("Should convert PNG to ImageContent")
        void testPngToImageContent() throws IAttachmentStore.AttachmentStoreException {
            byte[] data = new byte[]{10, 20, 30};
            Attachment attachment = new Attachment("ref-2", "image.png", "image/png", 3, "conv-1");
            when(attachmentStore.load("ref-2", "conv-1")).thenReturn(data);

            List<Content> result = forwarder.toContent(List.of(attachment), attachmentStore, "conv-1");

            assertEquals(1, result.size());
            assertInstanceOf(ImageContent.class, result.get(0));
        }
    }

    @Nested
    @DisplayName("Non-Image Attachments")
    class NonImageTests {

        @Test
        @DisplayName("Should convert PDF to text marker")
        void testPdfToTextMarker() {
            Attachment attachment = new Attachment("ref-3", "doc.pdf", "application/pdf", 1024, "conv-1");

            List<Content> result = forwarder.toContent(List.of(attachment), attachmentStore, "conv-1");

            assertEquals(1, result.size());
            assertInstanceOf(TextContent.class, result.get(0));
            TextContent text = (TextContent) result.get(0);
            assertTrue(text.text().contains("doc.pdf"));
            assertTrue(text.text().contains("application/pdf"));
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorTests {

        @Test
        @DisplayName("Should create error marker when attachment load fails")
        void testLoadFailure() throws IAttachmentStore.AttachmentStoreException {
            Attachment attachment = new Attachment("ref-4", "missing.jpg", "image/jpeg", 100, "conv-1");
            when(attachmentStore.load("ref-4", "conv-1"))
                    .thenThrow(new IAttachmentStore.AttachmentStoreException("Not found"));

            List<Content> result = forwarder.toContent(List.of(attachment), attachmentStore, "conv-1");

            assertEquals(1, result.size());
            assertInstanceOf(TextContent.class, result.get(0));
            TextContent text = (TextContent) result.get(0);
            assertTrue(text.text().contains("could not be loaded"));
        }
    }

    @Nested
    @DisplayName("isImageType")
    class ImageTypeTests {

        @Test
        @DisplayName("Should recognize image MIME types")
        void testImageTypes() {
            assertTrue(AttachmentForwarder.isImageType("image/jpeg"));
            assertTrue(AttachmentForwarder.isImageType("image/png"));
            assertTrue(AttachmentForwarder.isImageType("image/gif"));
            assertTrue(AttachmentForwarder.isImageType("image/webp"));
            assertTrue(AttachmentForwarder.isImageType("image/bmp"));
        }

        @Test
        @DisplayName("Should reject non-image MIME types")
        void testNonImageTypes() {
            assertFalse(AttachmentForwarder.isImageType("application/pdf"));
            assertFalse(AttachmentForwarder.isImageType("audio/mpeg"));
            assertFalse(AttachmentForwarder.isImageType(null));
        }

        @Test
        @DisplayName("Should handle MIME with parameters")
        void testMimeWithParams() {
            assertTrue(AttachmentForwarder.isImageType("image/png; charset=utf-8"));
        }
    }
}
