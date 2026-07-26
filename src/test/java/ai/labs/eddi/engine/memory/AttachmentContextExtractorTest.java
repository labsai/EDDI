/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    // ==================== Stored References ====================

    @Nested
    class StoredReferences {

        @Test
        void shouldExtractStorageRefAttachment() {
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext(Map.of(
                    "storageRef", "abc-123",
                    "fileName", "report.pdf")));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);

            assertEquals(1, result.size());
            Attachment att = result.get(0);
            assertEquals("abc-123", att.getStorageRef());
            assertEquals("report.pdf", att.getFileName());
            assertEquals(Attachment.ContentSource.STORED, att.getContentSource());
            // client MIME is never trusted for stored refs — resolved from store later
            assertNull(att.getMimeType());
        }

        @Test
        void shouldPreferStorageRefOverUrlAndData() {
            Map<String, Object> map = new HashMap<>();
            map.put("storageRef", "ref-1");
            map.put("url", "https://example.com/x.png");
            map.put("data", "base64garbage");
            map.put("mimeType", "image/png");
            Map<String, Context> contexts = new HashMap<>();
            contexts.put("attachment_0", createContext(map));

            List<Attachment> result = AttachmentContextExtractor.extractAttachments(contexts);

            assertEquals(1, result.size());
            assertEquals(Attachment.ContentSource.STORED, result.get(0).getContentSource());
            assertEquals("ref-1", result.get(0).getStorageRef());
            assertNull(result.get(0).getUrl());
            assertNull(result.get(0).getBase64Data());
        }
    }

    // ==================== resolveAndGuard ====================

    @Nested
    class ResolveAndGuard {

        @Test
        void shouldResolveStoredMetadataFromStore() throws Exception {
            var stored = new Attachment();
            stored.setStorageRef("ref-1");
            var store = mock(IAttachmentStore.class);
            when(store.getMetadata("ref-1", "conv-1")).thenReturn(
                    new IAttachmentStore.Attachment("ref-1", "doc.pdf", "application/pdf", 2048, "conv-1"));

            var result = AttachmentContextExtractor.resolveAndGuard(List.of(stored), store, "conv-1", 5);

            assertTrue(result.errors().isEmpty());
            assertEquals(1, result.attachments().size());
            Attachment resolved = result.attachments().get(0);
            assertEquals("application/pdf", resolved.getMimeType());
            assertEquals("doc.pdf", resolved.getFileName());
            assertEquals(2048, resolved.getSizeBytes());
        }

        @Test
        void shouldKeepClientFileNameHint() throws Exception {
            var stored = new Attachment();
            stored.setStorageRef("ref-1");
            stored.setFileName("client-name.pdf");
            var store = mock(IAttachmentStore.class);
            when(store.getMetadata(eq("ref-1"), any())).thenReturn(
                    new IAttachmentStore.Attachment("ref-1", "server-name.pdf", "application/pdf", 10, "conv-1"));

            var result = AttachmentContextExtractor.resolveAndGuard(List.of(stored), store, "conv-1", 5);

            assertEquals("client-name.pdf", result.attachments().get(0).getFileName());
        }

        @Test
        void shouldRecordErrorWhenNoStoreConfigured() {
            var stored = new Attachment();
            stored.setStorageRef("ref-1");

            var result = AttachmentContextExtractor.resolveAndGuard(List.of(stored), null, "conv-1", 5);

            assertTrue(result.attachments().isEmpty());
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().get(0).contains("no attachment store"));
        }

        @Test
        void shouldRecordErrorWhenResolutionFails() throws Exception {
            var stored = new Attachment();
            stored.setStorageRef("bad-ref");
            var store = mock(IAttachmentStore.class);
            when(store.getMetadata(any(), any()))
                    .thenThrow(new IAttachmentStore.AttachmentStoreException("Attachment not found: bad-ref"));

            var result = AttachmentContextExtractor.resolveAndGuard(List.of(stored), store, "conv-1", 5);

            assertTrue(result.attachments().isEmpty());
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().get(0).contains("could not be resolved"));
        }

        @Test
        void shouldPassThroughUrlAndBase64Unchanged() {
            var url = new Attachment();
            url.setMimeType("image/png");
            url.setUrl("https://example.com/x.png");
            var b64 = new Attachment();
            b64.setMimeType("image/png");
            b64.setBase64Data("iVBOR");

            var result = AttachmentContextExtractor.resolveAndGuard(List.of(url, b64), null, "conv-1", 5);

            assertTrue(result.errors().isEmpty());
            assertEquals(2, result.attachments().size());
        }

        @Test
        void shouldEnforcePerTurnCap() {
            var a = urlAttachment();
            var b = urlAttachment();
            var c = urlAttachment();

            var result = AttachmentContextExtractor.resolveAndGuard(List.of(a, b, c), null, "conv-1", 2);

            assertEquals(2, result.attachments().size());
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().get(0).contains("per-turn limit"));
        }

        @Test
        void shouldTreatNonPositiveCapAsUnlimited() {
            var list = List.of(urlAttachment(), urlAttachment(), urlAttachment());
            var result = AttachmentContextExtractor.resolveAndGuard(list, null, "conv-1", 0);
            assertEquals(3, result.attachments().size());
            assertTrue(result.errors().isEmpty());
        }

        private Attachment urlAttachment() {
            var a = new Attachment();
            a.setMimeType("image/png");
            a.setUrl("https://example.com/x.png");
            return a;
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

    // ==================== Earlier-turn lookup ====================

    /**
     * {@link AttachmentContextExtractor#attachmentsFromPreviousTurns} is what keeps
     * an uploaded file reachable after the turn it arrived on — both for the
     * reminder note on the outgoing message and for offering the readAttachment
     * tool at all.
     */
    @Nested
    class AttachmentsFromPreviousTurns {

        @Test
        void shouldReturnEmptyForNullMemory() {
            assertTrue(AttachmentContextExtractor.attachmentsFromPreviousTurns(null).isEmpty());
        }

        @Test
        void shouldReturnEmptyWhenThereAreNoPreviousSteps() {
            IConversationMemory memory = mock(IConversationMemory.class);
            when(memory.getPreviousSteps()).thenReturn(null);
            assertTrue(AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).isEmpty());

            IConversationMemory.IConversationStepStack empty = mock(IConversationMemory.IConversationStepStack.class);
            when(empty.size()).thenReturn(0);
            when(memory.getPreviousSteps()).thenReturn(empty);
            assertTrue(AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).isEmpty());
        }

        @Test
        void shouldReturnEmptyWhenNoPreviousStepCarriedAttachments() {
            IConversationMemory memory = memoryWithSteps(List.of(), List.of());
            assertTrue(AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).isEmpty());
        }

        @Test
        void shouldCollectAttachmentsAcrossSteps() {
            Attachment pdf = stored("ref-pdf", "report.pdf");
            Attachment image = stored("ref-img", "chart.png");
            // Step 0 is the most recent previous turn.
            IConversationMemory memory = memoryWithSteps(List.of(image), List.of(pdf));

            List<Attachment> result = AttachmentContextExtractor.attachmentsFromPreviousTurns(memory);

            assertEquals(2, result.size());
            assertEquals("chart.png", result.get(0).getFileName(), "newest turn first");
            assertEquals("report.pdf", result.get(1).getFileName());
        }

        @Test
        void shouldDeduplicateTheSameFileResentAcrossTurns() {
            IConversationMemory memory = memoryWithSteps(
                    List.of(stored("ref-pdf", "report.pdf")),
                    List.of(stored("ref-pdf", "report.pdf")));

            List<Attachment> result = AttachmentContextExtractor.attachmentsFromPreviousTurns(memory);

            assertEquals(1, result.size());
        }

        /**
         * Only blob-backed attachments can be fetched on a later turn. A URL reference
         * is not in the attachment store and an inline base64 payload is never
         * persisted, so surfacing either would advertise a file that readAttachment
         * then fails to fetch.
         */
        @Test
        void shouldOmitAttachmentsThatCannotBeFetchedLater() {
            Attachment byUrl = new Attachment();
            byUrl.setUrl("https://example.com/a.png");
            byUrl.setMimeType("image/png");

            Attachment inline = new Attachment();
            inline.setMimeType("application/pdf");
            inline.setFileName("inline.pdf");
            inline.setBase64Data("JVBERi0=");

            IConversationMemory memory = memoryWithSteps(List.of(byUrl), List.of(inline));

            assertTrue(AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).isEmpty());
        }

        /**
         * The regression that mattered: everything before the current turn has been
         * through the conversation store, where {@code ResultSnapshot#result} is a bare
         * Object — so attachments come back as maps, not Attachments. Both the Mongo
         * and Postgres stores repair only {@code context*} entries on load, so an
         * {@code instanceof Attachment} test alone matched nothing from turn two
         * onwards, which is precisely when this lookup is needed.
         */
        @Test
        void shouldRecoverAttachmentsAfterAConversationStoreRoundTrip() throws Exception {
            ConversationMemory memory = new ConversationMemory("conv-1", "agent-1", 1, "user-1");
            Attachment pdf = new Attachment("application/pdf", "report.pdf", 1234L, "ref-1");
            memory.getCurrentStep().storeData(
                    new ai.labs.eddi.engine.memory.model.Data<>(MemoryKeys.ATTACHMENTS.key(), List.of(pdf)));
            memory.startNextStep(); // the follow-up turn, carrying no file of its own

            // Persist and reload the way both stores do: Jackson to JSON and back
            // into the snapshot, then rebuild the in-memory representation.
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            ConversationMemorySnapshot snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
            ConversationMemorySnapshot reloaded = mapper.readValue(
                    mapper.writeValueAsString(snapshot), ConversationMemorySnapshot.class);
            IConversationMemory restored = ConversationMemoryUtilities.convertConversationMemorySnapshot(reloaded);

            List<Attachment> result = AttachmentContextExtractor.attachmentsFromPreviousTurns(restored);

            assertEquals(1, result.size(), "an earlier turn's file must survive the store round trip");
            assertEquals("report.pdf", result.get(0).getFileName());
            assertEquals("ref-1", result.get(0).getStorageRef());
            assertEquals("application/pdf", result.get(0).getMimeType());
            assertEquals(1234L, result.get(0).getSizeBytes());
            assertEquals(Attachment.ContentSource.STORED, result.get(0).getContentSource());
        }

        @Test
        void shouldRecoverTheMapFormWrittenBackByTheStore() {
            // The exact shape Jackson hands back for a persisted Attachment.
            Map<String, Object> persisted = new HashMap<>();
            persisted.put("mimeType", "application/pdf");
            persisted.put("fileName", "earlier.pdf");
            persisted.put("storageRef", "ref-9");
            persisted.put("sizeBytes", 42);

            IConversationMemory memory = memoryWithSteps(List.of(persisted));

            List<Attachment> result = AttachmentContextExtractor.attachmentsFromPreviousTurns(memory);

            assertEquals(1, result.size());
            assertEquals("earlier.pdf", result.get(0).getFileName());
            assertEquals(42L, result.get(0).getSizeBytes());
        }

        @Test
        void shouldIgnoreMapsThatAreNotAttachments() {
            IConversationMemory memory = memoryWithSteps(List.of(Map.of("some", "unrelated data")));
            assertTrue(AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).isEmpty());
        }

        @Test
        void shouldDeduplicateAcrossLiveAndPersistedForms() {
            Map<String, Object> persisted = new HashMap<>();
            persisted.put("storageRef", "ref-1");
            persisted.put("fileName", "report.pdf");
            persisted.put("mimeType", "application/pdf");

            IConversationMemory memory = memoryWithSteps(
                    List.of(stored("ref-1", "report.pdf")),
                    List.of(persisted));

            assertEquals(1, AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).size());
        }

        @Test
        void shouldIgnoreNonAttachmentEntries() {
            IConversationMemory memory = memoryWithSteps(List.of("not an attachment", 42));
            assertTrue(AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).isEmpty());
        }

        private static Attachment stored(String ref, String fileName) {
            Attachment att = new Attachment();
            att.setStorageRef(ref);
            att.setFileName(fileName);
            att.setMimeType("application/pdf");
            return att;
        }

        /**
         * Build a memory whose previous-step stack yields one step per argument, in the
         * order given (index 0 = most recent, matching the real stack).
         */
        @SafeVarargs
        @SuppressWarnings({"unchecked", "rawtypes"})
        private static IConversationMemory memoryWithSteps(List<Object>... stepAttachments) {
            IConversationMemory memory = mock(IConversationMemory.class);
            IConversationMemory.IConversationStepStack stack = mock(IConversationMemory.IConversationStepStack.class);
            when(stack.size()).thenReturn(stepAttachments.length);

            for (int i = 0; i < stepAttachments.length; i++) {
                IConversationMemory.IConversationStep step = mock(IConversationMemory.IConversationStep.class);
                if (stepAttachments[i].isEmpty()) {
                    when(step.getData(MemoryKeys.ATTACHMENTS)).thenReturn(null);
                } else {
                    IData data = mock(IData.class);
                    when(data.getResult()).thenReturn(stepAttachments[i]);
                    when(step.getData(MemoryKeys.ATTACHMENTS)).thenReturn(data);
                }
                when(stack.get(i)).thenReturn(step);
            }

            when(memory.getPreviousSteps()).thenReturn(stack);
            return memory;
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
