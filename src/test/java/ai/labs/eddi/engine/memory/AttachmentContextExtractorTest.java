/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AttachmentContextExtractor}.
 */
class AttachmentContextExtractorTest {

    // ==================== URL References ====================

    @Nested
    class UrlReferences {

        @Test
        void shouldExtractUrlAttachment() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext(Map.of(
                    "mimeType", "image/png",
                    "url", "https://example.com/image.png",
                    "fileName", "image.png")));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);

            assertEquals(1, result.size());
            Attachment att = result.get(0);
            assertEquals("image/png", att.getMimeType());
            assertEquals("https://example.com/image.png", att.getUrl());
            assertEquals("image.png", att.getFileName());
            assertNull(att.getBase64Data());
            assertEquals(Attachment.ContentSource.URL, att.getContentSource());
        }

        @Test
        void shouldExtractMultipleAttachments() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext(Map.of(
                    "mimeType", "image/png", "url", "https://example.com/1.png")));
            contexts.put("attachment_1", createContext(Map.of(
                    "mimeType", "image/jpeg", "url", "https://example.com/2.jpg")));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);

            assertEquals(2, result.size());
        }
    }

    // ==================== Base64 Inline ====================

    @Nested
    class Base64Inline {

        @Test
        void shouldExtractBase64Attachment() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext(Map.of(
                    "mimeType", "image/png",
                    "data", "iVBORw0KGgo=",
                    "fileName", "icon.png")));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);

            assertEquals(1, result.size());
            Attachment att = result.get(0);
            assertEquals("image/png", att.getMimeType());
            assertEquals("iVBORw0KGgo=", att.getBase64Data());
            assertEquals("icon.png", att.getFileName());
            assertNull(att.getUrl());
            assertEquals(Attachment.ContentSource.BASE64, att.getContentSource());
            assertTrue(att.getSizeBytes() > 0, "Size should be estimated from base64 length");
        }
    }

    // ==================== Edge Cases ====================

    @Nested
    class EdgeCases {

        @Test
        void shouldReturnEmptyListForNullContexts() {
            List<Attachment> result = AttachmentContextExtractor.extractAttachments(null);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldReturnEmptyListForEmptyContexts() {
            List<Attachment> result = AttachmentContextExtractor.extractAttachments(Map.of());
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldIgnoreNonAttachmentContextKeys() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("language", createContext("en"));
            contexts.put("userId", createContext("user123"));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldSkipAttachmentWithoutMimeType() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext(Map.of(
                    "url", "https://example.com/file")));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldSkipAttachmentWithoutUrlOrData() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext(Map.of(
                    "mimeType", "image/png",
                    "fileName", "orphan.png")));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldSkipNonMapContextValues() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext("not a map"));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldSkipNullContextValue() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", new Context());

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);
            assertTrue(result.isEmpty());
        }

        @Test
        void shouldPreferUrlOverBase64() {
            Map<String, Object> attachMap = new HashMap<>();
            attachMap.put("mimeType", "image/png");
            attachMap.put("url", "https://example.com/image.png");
            attachMap.put("data", "base64garbage");

            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext(attachMap));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);

            assertEquals(1, result.size());
            assertEquals("https://example.com/image.png", result.get(0).getUrl());
            // Base64 should not be set when URL is present
            assertNull(result.get(0).getBase64Data());
        }
    }

    // ==================== Payload Scrubbing (persistence) ====================

    @Nested
    class ScrubInlinePayload {

        @Test
        void shouldRemoveBase64DataFromAttachmentContext() {
            Map<String, Object> attachMap = new HashMap<>();
            attachMap.put("mimeType", "image/png");
            attachMap.put("fileName", "icon.png");
            attachMap.put("data", "iVBORw0KGgoAAAANSUhEUgAA");
            Context original = createContext(attachMap);

            Context scrubbed = AttachmentContextExtractor.scrubInlinePayload("attachment_0", original);

            assertNotSame(original, scrubbed, "a scrubbed copy must be returned, not the original");
            @SuppressWarnings("unchecked")
            Map<Object, Object> value = (Map<Object, Object>) scrubbed.getValue();
            assertFalse(value.containsKey("data"), "base64 data must be scrubbed from the persisted copy");
            assertEquals("image/png", value.get("mimeType"), "metadata must be preserved");
            assertEquals("icon.png", value.get("fileName"), "metadata must be preserved");
            assertEquals(original.getType(), scrubbed.getType(), "context type must be preserved");
        }

        @Test
        void shouldNotMutateOriginalContext() {
            Map<String, Object> attachMap = new HashMap<>();
            attachMap.put("mimeType", "image/png");
            attachMap.put("data", "iVBORw0KGgo=");
            Context original = createContext(attachMap);

            AttachmentContextExtractor.scrubInlinePayload("attachment_0", original);

            @SuppressWarnings("unchecked")
            Map<Object, Object> originalValue = (Map<Object, Object>) original.getValue();
            assertTrue(originalValue.containsKey("data"),
                    "original context must keep its payload so the live turn can forward it");
            assertEquals("iVBORw0KGgo=", originalValue.get("data"));
        }

        @Test
        void shouldReturnSameInstanceForUrlAttachment() {
            Context original = createContext(Map.of(
                    "mimeType", "image/png", "url", "https://example.com/x.png"));

            Context result = AttachmentContextExtractor.scrubInlinePayload("attachment_0", original);

            assertSame(original, result, "url-only attachments carry no payload — return unchanged");
        }

        @Test
        void shouldReturnSameInstanceForNonAttachmentKey() {
            Context original = createContext(Map.of("data", "somevalue"));

            Context result = AttachmentContextExtractor.scrubInlinePayload("language", original);

            assertSame(original, result, "non-attachment contexts must never be scrubbed");
        }

        @Test
        void shouldHandleNullContext() {
            assertNull(AttachmentContextExtractor.scrubInlinePayload("attachment_0", null));
        }

        @Test
        void shouldHandleNullKey() {
            Context original = createContext(Map.of("data", "x"));
            assertSame(original, AttachmentContextExtractor.scrubInlinePayload(null, original));
        }

        @Test
        void shouldReturnUnchangedWhenValueNotAMap() {
            Context original = createContext("not a map");
            assertSame(original, AttachmentContextExtractor.scrubInlinePayload("attachment_0", original));
        }
    }

    // ==================== Helpers ====================

    private static Context createContext(Object value) {
        Context ctx = new Context();
        ctx.setType(Context.ContextType.object);
        ctx.setValue(value);
        return ctx;
    }
}
