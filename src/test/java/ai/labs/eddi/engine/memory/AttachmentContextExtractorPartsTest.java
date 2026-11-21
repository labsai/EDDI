/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.documents.SelectorException;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Parts of a stored file in {@code attachment_N} context and in step data: what
 * is read from a client, how it is resolved at turn intake, and that it
 * survives the conversation store. Ids are 24-hex.
 */
class AttachmentContextExtractorPartsTest {

    private static final String CONV = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String REF = "6560a1b2c3d4e5f6a7b8c9d0";
    private static final String VIEW = "6560a1b2c3d4e5f6a7b8c9d1";
    private static final String SPLIT_X = "0123456789abcdef";

    private static Map<String, Context> context(Map<String, Object> value) {
        return Map.of("attachment_0", new Context(Context.ContextType.object, value));
    }

    private static Attachment parse(Map<String, Object> value) {
        List<Attachment> parsed = AttachmentContextExtractor.extractAttachments(context(value));
        assertEquals(1, parsed.size());
        return parsed.get(0);
    }

    private static Map<String, Object> ref(Object... fields) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("storageRef", REF);
        for (int i = 0; i < fields.length; i += 2) {
            value.put((String) fields[i], fields[i + 1]);
        }
        return value;
    }

    @Nested
    class Parsing {

        @Test
        void partFields_areReadIntoMetadata() {
            Attachment a = parse(ref("pages", " 12-19 ", "unit", "u3", "split", SPLIT_X));

            assertEquals(new AttachmentSelector(REF, "12-19", "u3", SPLIT_X), AttachmentSelector.fromAttachment(a));
        }

        @Test
        void derivedMarks_fromAClient_areIgnored() {
            Attachment a = parse(ref("doc.derived", "true", "doc.root", VIEW, "metadata", Map.of("doc.derived", "true")));

            assertTrue(a.getMetadata() == null || a.getMetadata().isEmpty(), String.valueOf(a.getMetadata()));
        }

        @Test
        void nonStringPartFields_areIgnored() {
            Attachment a = parse(ref("pages", 7, "unit", List.of("u1")));
            assertTrue(AttachmentSelector.fromAttachment(a).isWholeFile());
        }

        @Test
        void overlongField_isCut_notDropped() {
            Attachment a = parse(ref("pages", "1".repeat(500)));
            assertEquals(AttachmentContextExtractor.MAX_PART_FIELD_CHARS, a.getMetadata().get(AttachmentSelector.META_PAGES).length());
        }

        @Test
        void urlAndInlineAttachments_carryNoPart() {
            Attachment url = parse(Map.of("mimeType", "image/png", "url", "https://example.com/a.png", "pages", "1-2"));
            assertTrue(url.getMetadata() == null || url.getMetadata().isEmpty());
        }
    }

    @Nested
    class Intake {

        private IAttachmentStore store;
        private final List<String[]> calls = new ArrayList<>();

        @BeforeEach
        void setUp() throws Exception {
            store = mock(IAttachmentStore.class);
            when(store.getMetadata(eq(REF), eq(CONV))).thenReturn(new IAttachmentStore.Attachment(REF, "report.pdf", "application/pdf", 10, CONV));
        }

        private AttachmentContextExtractor.PartResolver resolver(String pages) {
            return (storageRef, conversationId, unit, p, split) -> {
                calls.add(new String[]{storageRef, conversationId, unit, p, split});
                return new AttachmentSelector(storageRef, p != null ? p : pages, unit, unit != null ? SPLIT_X : null);
            };
        }

        private AttachmentContextExtractor.ExtractionResult intake(Map<String, Object> value,
                                                                   AttachmentContextExtractor.PartResolver resolver) {
            return AttachmentContextExtractor.resolveAndGuard(AttachmentContextExtractor.extractAttachments(context(value)), store, CONV, 5,
                    resolver);
        }

        @Test
        void unitOnly_isResolvedOnce_andCarriesPagesAndSplit() {
            var result = intake(ref("unit", "u3"), resolver("12-19"));

            assertTrue(result.errors().isEmpty(), result.errors().toString());
            assertEquals(new AttachmentSelector(REF, "12-19", "u3", SPLIT_X), AttachmentSelector.fromAttachment(result.attachments().get(0)));
            assertEquals(1, calls.size());
            assertEquals(CONV, calls.get(0)[1], "resolved for the receiving conversation");
        }

        @Test
        void partWithoutAResolver_isDropped_neverSentWhole() {
            var result = AttachmentContextExtractor.resolveAndGuard(
                    AttachmentContextExtractor.extractAttachments(context(ref("pages", "1-2"))), store, CONV, 5);

            assertTrue(result.attachments().isEmpty());
            assertEquals(1, result.errors().size());
            assertTrue(result.errors().get(0).contains("parts of a file cannot be sent here"), result.errors().get(0));
        }

        @Test
        void aRefusedPart_isDroppedWithANote() {
            AttachmentContextExtractor.PartResolver refusing = (s, c, u, p, sp) -> {
                throw new SelectorException(SelectorException.Reason.FOREIGN_SPLIT,
                        "this chapter id belongs to a different split of the file; send pages");
            };

            var result = intake(ref("unit", "u3", "split", "fedcba9876543210"), refusing);

            assertTrue(result.attachments().isEmpty(), "never a whole-file fallback");
            assertTrue(result.errors().get(0).contains("different split"), result.errors().get(0));
        }

        @Test
        void anUnexpectedFailure_isDropped_withoutItsMessage() {
            AttachmentContextExtractor.PartResolver broken = (s, c, u, p, sp) -> {
                throw new IllegalStateException("secret internals " + s);
            };

            var result = intake(ref("pages", "1-2"), broken);

            assertTrue(result.attachments().isEmpty());
            assertFalse(result.errors().get(0).contains("secret internals"));
        }

        @Test
        void aWholeFile_neverAsksTheResolver() {
            var result = intake(ref(), resolver("1-1"));

            assertEquals(1, result.attachments().size());
            assertTrue(calls.isEmpty());
        }

        @Test
        void aForeignReference_withAPart_isRefusedAsToday() throws Exception {
            when(store.getMetadata(eq(VIEW), any())).thenThrow(new IAttachmentStore.AttachmentAccessDeniedException("Access denied"));
            Map<String, Object> foreign = new LinkedHashMap<>(Map.of("storageRef", VIEW, "pages", "1-2"));

            var result = intake(foreign, resolver("1-2"));

            assertTrue(result.attachments().isEmpty());
            assertTrue(calls.isEmpty(), "authorization comes first");
        }

        @Test
        void eachPart_countsAsOneAttachment() throws Exception {
            List<Attachment> parsed = new ArrayList<>();
            for (int i = 1; i <= 7; i++) {
                parsed.addAll(AttachmentContextExtractor.extractAttachments(context(ref("pages", i + "-" + i))));
            }

            var result = AttachmentContextExtractor.resolveAndGuard(parsed, store, CONV, 5, resolver(null));

            assertEquals(5, result.attachments().size());
            assertEquals(2, result.errors().size());
        }
    }

    @Nested
    class StepData {

        private ConversationMemory memory;

        @BeforeEach
        void setUp() {
            memory = new ConversationMemory(CONV, "64b7f0c2a1d3e4f5a6b7c8e0", 1, "user-1");
        }

        private static Attachment part(String pages) {
            Attachment a = new Attachment("application/pdf", "report.pdf", 10, REF);
            a.getMetadata().putAll(new AttachmentSelector(REF, pages, null, null).toMetadata());
            return a;
        }

        private static Attachment recorded(String ref, String pages, String root) {
            Attachment a = new Attachment("application/pdf", "report.pdf", 10, ref);
            a.getMetadata().putAll(new AttachmentSelector(ref, pages, "u3", SPLIT_X).toMetadata());
            a.getMetadata().put(AttachmentSelector.META_DERIVED, "true");
            a.getMetadata().put(AttachmentSelector.META_ROOT, root);
            return a;
        }

        private IConversationMemory roundTrip() throws Exception {
            ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
            ConversationMemorySnapshot snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
            return ConversationMemoryUtilities
                    .convertConversationMemorySnapshot(mapper.readValue(mapper.writeValueAsString(snapshot), ConversationMemorySnapshot.class));
        }

        private void withholdCurrentStep() {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.LLM_INPUT_WITHHELD_PREFIX + "llm", Boolean.TRUE));
        }

        @Test
        void aPart_survivesTheConversationStore() throws Exception {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(), List.of(part("12-19"))));
            memory.startNextStep();

            List<Attachment> earlier = AttachmentContextExtractor.attachmentsFromPreviousTurns(roundTrip());

            assertEquals(1, earlier.size());
            assertEquals("12-19", earlier.get(0).getMetadata().get(AttachmentSelector.META_PAGES),
                    "a resumed turn must not widen a part to the whole file");
        }

        @Test
        void twoPartsOfOneFile_areNotCollapsed() {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(), List.of(part("1-2"), part("5-9"))));
            memory.startNextStep();

            assertEquals(2, AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).size());
        }

        @Test
        void recordedDerived_findsMarkedEntries_onEveryStep_afterAStoreRoundTrip() throws Exception {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(), List.of(recorded(REF, "12-19", REF))));
            memory.startNextStep();
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(), List.of(part("1-1"), recorded(REF, "20-24", REF))));

            List<Attachment> recorded = AttachmentContextExtractor.recordedDerived(roundTrip());

            assertEquals(Set.of("12-19", "20-24"),
                    Set.copyOf(recorded.stream().map(a -> a.getMetadata().get(AttachmentSelector.META_PAGES)).toList()));
        }

        @Test
        void recordedDerived_skipsWithheldTurns() {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(), List.of(recorded(REF, "12-19", REF))));
            withholdCurrentStep();

            assertTrue(AttachmentContextExtractor.recordedDerived(memory).isEmpty());
            assertTrue(AttachmentContextExtractor.recordedDerived(null).isEmpty());
        }

        @Test
        void aViewRecordedOnAWithheldTurn_withholdsItsRoot() {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(), List.of(recorded(VIEW, null, REF))));
            withholdCurrentStep();
            memory.startNextStep();

            Set<String> withheld = AttachmentContextExtractor.withheldStorageRefs(memory);

            assertTrue(withheld.contains(REF), "a child of a withheld file withholds the file");
            assertTrue(withheld.contains(VIEW));
        }

        @Test
        void identity_distinguishesParts() {
            Attachment whole = new Attachment("application/pdf", "report.pdf", 10, REF);
            assertEquals("ref:" + REF, AttachmentContextExtractor.identity(whole));
            assertEquals("ref:" + REF + "#p1-2", AttachmentContextExtractor.identity(part("1-2")));
            assertNull(AttachmentSelector.fromAttachment(new Attachment("image/png", "x.png", 1, null)));
        }
    }
}
