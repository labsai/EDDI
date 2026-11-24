/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentDerivedCapException;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.ingestion.extract.PdfFixtures;
import ai.labs.eddi.modules.ingestion.extract.PdfSliceBuilder;
import ai.labs.eddi.modules.ingestion.extract.PdfSliceBuilder.SliceTooLargeException;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import ai.labs.eddi.modules.llm.capability.ModelCapabilityService;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AttachmentForwarder} with a stored PDF that names a page range: a
 * document-capable model gets a PDF of those pages behind a header, any other
 * model those pages' text, and no outcome sends the whole file.
 */
class AttachmentForwarderPartsTest {

    private static final String CONV = "64b7f0c2a1d3e4f5a6b7c8d9";
    private static final String AGENT = "64b7f0c2a1d3e4f5a6b7c8e0";
    private static final String REF = "65a1b2c3d4e5f60718293a4b";
    private static final String SLICE_REF = "65a1b2c3d4e5f60718293a4c";
    private static final long MB = 1024L * 1024;

    /** Reads PDFs natively. */
    private static final String DOC_PROVIDER = "anthropic";
    private static final String DOC_MODEL = "claude-sonnet-4";
    /** Does not, by default. */
    private static final String TEXT_PROVIDER = "openai";
    private static final String TEXT_MODEL = "gpt-4o";

    @TempDir
    Path workDir;

    private IAttachmentStore store;
    private PdfSliceBuilder sliceBuilder;
    private ConversationMemory memory;
    private byte[] pdf;

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IAttachmentStore.class);
        sliceBuilder = spy(new PdfSliceBuilder());
        memory = new ConversationMemory(CONV, AGENT, 1, "user-1");
        pdf = PdfFixtures.plain(8);
        when(store.load(REF, CONV)).thenReturn(pdf);
        when(store.findDerived(anyString(), any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(store.pageCount(REF, CONV)).thenReturn(8);
    }

    private AttachmentForwarder forwarder(long perFile, long aggregate) {
        return new AttachmentForwarder(store, new ModelCapabilityService(k -> Optional.empty()), new AttachmentTextExtractor(50_000),
                mock(OutboundHttp.class), sliceBuilder, workDir, new SimpleMeterRegistry(), perFile, aggregate);
    }

    private AttachmentForwarder forwarder() {
        return forwarder(10 * MB, 20 * MB);
    }

    @Nested
    class DocumentModel {

        @Test
        void getsAHeaderThenAPdfOfOnlyThosePages() throws Exception {
            attach(part("3-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            List<Content> contents = contents(messages);
            assertEquals(3, contents.size(), contents.toString());
            String header = assertInstanceOf(TextContent.class, contents.get(1)).text();
            assertTrue(header.contains("report.pdf"), header);
            assertTrue(header.contains("3–5"), header);
            byte[] sent = Base64.getDecoder().decode(assertInstanceOf(PdfFileContent.class, contents.get(2)).pdfFile().base64Data());
            try (PDDocument slice = Loader.loadPDF(sent)) {
                assertEquals(3, slice.getNumberOfPages());
                assertTrue(text(slice).startsWith("Page 3 line 1:"), text(slice));
            }
        }

        @Test
        void aUnitLabelIsNamedInTheHeader() {
            Attachment att = part("3-5");
            att.getMetadata().putAll(new AttachmentSelector(REF, "3-5", "u2", "abc123").toMetadata());
            attach(att);
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            String header = ((TextContent) contents(messages).get(1)).text();
            assertTrue(header.contains("u2"), header);
        }

        @Test
        void theSliceIsCachedAsADerivedBlobOfTheRoot() throws Exception {
            attach(part("3-5"));

            forwarder().forward(question(), memory, DOC_PROVIDER, DOC_MODEL);

            var derivation = ArgumentCaptor.forClass(Derivation.class);
            verify(store).storeDerived(any(byte[].class), eq("application/pdf"), eq("report (p3-5).pdf"), derivation.capture(),
                    eq(CONV));
            assertEquals(REF, derivation.getValue().rootRef());
            assertEquals(Derivation.Kind.PDF_SLICE, derivation.getValue().kind());
            assertEquals("p3-5", derivation.getValue().unitId());
        }

        @Test
        void aCachedSliceIsSentWithoutBuildingAgain() throws Exception {
            byte[] cached = Files.readAllBytes(new PdfSliceBuilder().slice(pdf, 3, 5, 10 * MB, workDir));
            when(store.findDerived(REF, Derivation.Kind.PDF_SLICE, "p3-5", CONV))
                    .thenReturn(Optional.of(new IAttachmentStore.Attachment(SLICE_REF, "report (p3-5).pdf", "application/pdf",
                            cached.length, CONV)));
            when(store.load(SLICE_REF, CONV)).thenReturn(cached);
            attach(part("3-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            byte[] sent = Base64.getDecoder().decode(((PdfFileContent) contents(messages).get(2)).pdfFile().base64Data());
            assertArrayEquals(cached, sent);
            verify(sliceBuilder, never()).slice(any(), anyInt(), anyInt(), anyLong(), any());
            verify(store, never()).storeDerived(any(), any(), any(), any(), any());
        }

        @Test
        void aSliceTheStoreWillNotKeepIsStillSent() throws Exception {
            when(store.storeDerived(any(), any(), any(), any(), any())).thenThrow(new AttachmentDerivedCapException("cap", "PDF_SLICE"));
            attach(part("3-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            assertInstanceOf(PdfFileContent.class, contents(messages).get(2));
        }

        @Test
        void theWorkFileIsDeletedAfterwards() {
            attach(part("3-5"));

            forwarder().forward(question(), memory, DOC_PROVIDER, DOC_MODEL);

            assertNoWorkFiles();
        }

        @Test
        void aFileOverThePerFileLimitCanStillSendASmallPart() throws Exception {
            byte[] big = PdfFixtures.plain(60);
            when(store.load(REF, CONV)).thenReturn(big);
            when(store.pageCount(REF, CONV)).thenReturn(60);
            attach(part("2-2"));
            List<ChatMessage> messages = question();

            // The whole file is over the limit; one page of it is not.
            forwarder(big.length / 2, 20 * MB).forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            assertInstanceOf(PdfFileContent.class, contents(messages).get(2));
        }
    }

    @Nested
    class Fallbacks {

        @Test
        void aTextModelGetsOnlyThosePagesText() throws Exception {
            attach(part("3-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, TEXT_PROVIDER, TEXT_MODEL);

            List<Content> contents = contents(messages);
            assertEquals(2, contents.size(), contents.toString());
            String text = assertInstanceOf(TextContent.class, contents.get(1)).text();
            assertTrue(text.startsWith("[Attachment report.pdf — p.3–5"), text);
            assertTrue(text.contains("Page 3 line 1:") && text.contains("Page 5 line 1:"), text);
            assertFalse(text.contains("Page 2 line") || text.contains("Page 6 line"), "only the selected pages: " + text);
            verify(sliceBuilder, never()).slice(any(), anyInt(), anyInt(), anyLong(), any());
            verify(store, never()).storeDerived(any(), any(), any(), any(), any());
        }

        @Test
        void aSliceTooLargeBecomesTheRangesText() {
            // doThrow, not when(): stubbing a spy with when() calls the real method.
            doThrow(new SliceTooLargeException(10 * MB)).when(sliceBuilder).slice(any(), anyInt(), anyInt(), anyLong(), any());
            attach(part("3-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            assertOnlyRangeText(messages);
            assertTrue(errors().stream().anyMatch(e -> e.contains("larger than")), errors().toString());
        }

        @Test
        void aSliceThatCannotBeBuiltBecomesTheRangesText() {
            doThrow(new UnreadableDocumentException("broken")).when(sliceBuilder).slice(any(), anyInt(), anyInt(), anyLong(), any());
            attach(part("3-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            assertOnlyRangeText(messages);
        }

        @Test
        void theRequestBudgetIsChargedTheSliceAndNotTheFile() throws Exception {
            long slice = Files.size(new PdfSliceBuilder().slice(pdf, 3, 5, 10 * MB, Files.createDirectory(workDir.resolve("measure"))));
            attach(part("3-5"), part("3-5"));
            List<ChatMessage> messages = question();

            // Room for one slice and a half — and far less than the whole file twice.
            forwarder(10 * MB, slice + slice / 2).forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            List<Content> contents = contents(messages);
            assertEquals(4, contents.size(), contents.toString());
            assertInstanceOf(PdfFileContent.class, contents.get(2));
            String second = assertInstanceOf(TextContent.class, contents.get(3)).text();
            assertTrue(second.contains("Page 3 line 1:"), "the second part is sent as text: " + second);
        }

        @Test
        void pagesBeyondTheDocumentSendNothingOfIt() throws Exception {
            when(store.pageCount(REF, CONV)).thenReturn(3);
            attach(part("2-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, TEXT_PROVIDER, TEXT_MODEL);

            List<Content> contents = contents(messages);
            assertEquals(2, contents.size());
            String note = ((TextContent) contents.get(1)).text();
            assertTrue(note.contains("not forwarded"), note);
            assertFalse(note.contains("Page 2 line"), note);
        }

        @Test
        void anInvalidPageRangeSendsNothingOfTheFile() throws Exception {
            Attachment att = new Attachment("application/pdf", "report.pdf", pdf.length, REF);
            att.getMetadata().put(AttachmentSelector.META_UNIT, "u3");
            attach(att);
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            List<Content> contents = contents(messages);
            assertEquals(2, contents.size());
            assertTrue(((TextContent) contents.get(1)).text().contains("not forwarded"));
            verify(store, never()).load(any(), any());
        }

        @Test
        void aCachedSliceOverTheLimitIsNeitherSentNorBuiltAgain() throws Exception {
            when(store.findDerived(REF, Derivation.Kind.PDF_SLICE, "p3-5", CONV))
                    .thenReturn(Optional.of(new IAttachmentStore.Attachment(SLICE_REF, "report (p3-5).pdf", "application/pdf",
                            11 * MB, CONV)));
            attach(part("3-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            assertOnlyRangeText(messages);
            verify(store, never()).load(SLICE_REF, CONV);
            verify(sliceBuilder, never()).slice(any(), anyInt(), anyInt(), anyLong(), any());
        }

        @Test
        void aFallbackThatEndsInANoteIsNotReportedAsSentText() throws Exception {
            // The slice fails (no page 4 or 5), and so does the text: neither was sent.
            when(store.load(REF, CONV)).thenReturn(PdfFixtures.plain(3));
            when(store.pageCount(REF, CONV)).thenReturn(3);
            attach(part("2-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            assertTrue(((TextContent) contents(messages).get(1)).text().contains("not forwarded"));
            assertTrue(errors().stream().noneMatch(e -> e.contains("was sent as text")), errors().toString());
        }

        @Test
        void aFallbackThatSendsTextIsReported() {
            doThrow(new UnreadableDocumentException("broken")).when(sliceBuilder).slice(any(), anyInt(), anyInt(), anyLong(), any());
            attach(part("3-5"));

            forwarder().forward(question(), memory, DOC_PROVIDER, DOC_MODEL);

            assertTrue(errors().stream().anyMatch(e -> e.contains("was sent as text")), errors().toString());
        }

        @Test
        void aPartOfAnythingButAPdfSendsNothingOfTheFile() throws Exception {
            Attachment att = new Attachment("text/plain", "notes.txt", 10, REF);
            att.getMetadata().putAll(new AttachmentSelector(REF, "1-1", null, null).toMetadata());
            attach(att);
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            List<Content> contents = contents(messages);
            assertEquals(2, contents.size(), contents.toString());
            assertTrue(((TextContent) contents.get(1)).text().contains("not forwarded"));
            verify(store, never()).load(any(), any());
        }
    }

    @Nested
    class Withheld {

        @Test
        void aPartOfAFileWithheldEarlierSendsNothing() throws Exception {
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(),
                    List.of(new Attachment("application/pdf", "report.pdf", pdf.length, REF))));
            memory.getCurrentStep().storeData(new Data<>(MemoryKeys.LLM_INPUT_WITHHELD_PREFIX + "llm", Boolean.TRUE));
            memory.startNextStep();
            attach(part("3-5"));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            assertEquals(1, contents(messages).size(), "only the question");
            verify(store, never()).load(any(), any());
            assertTrue(errors().stream().anyMatch(e -> e.contains("withheld")), errors().toString());
        }
    }

    @Nested
    class WholeFile {

        @Test
        void aPlainPdfIsSentWholeAsBefore() throws Exception {
            attach(new Attachment("application/pdf", "report.pdf", pdf.length, REF));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, DOC_PROVIDER, DOC_MODEL);

            List<Content> contents = contents(messages);
            assertEquals(2, contents.size());
            byte[] sent = Base64.getDecoder().decode(assertInstanceOf(PdfFileContent.class, contents.get(1)).pdfFile().base64Data());
            assertArrayEquals(pdf, sent);
            verify(store, never()).findDerived(any(), any(), any(), any());
            verify(store, never()).storeDerived(any(), any(), any(), any(), any());
            verify(sliceBuilder, never()).slice(any(), anyInt(), anyInt(), anyLong(), any());
        }

        @Test
        void aPlainPdfForATextModelIsTheWholeText() {
            attach(new Attachment("application/pdf", "report.pdf", pdf.length, REF));
            List<ChatMessage> messages = question();

            forwarder().forward(messages, memory, TEXT_PROVIDER, TEXT_MODEL);

            String text = ((TextContent) contents(messages).get(1)).text();
            assertTrue(text.startsWith("[Attachment report.pdf (application/pdf) — PDF text-extracted"), text);
            assertTrue(text.contains("Page 1 line 1:") && text.contains("Page 8 line 1:"), text);
        }
    }

    // ---------------------------------------------------------------------

    private static Attachment part(String pages) {
        Attachment att = new Attachment("application/pdf", "report.pdf", 0, REF);
        att.getMetadata().putAll(new AttachmentSelector(REF, pages, null, null).toMetadata());
        return att;
    }

    private void attach(Attachment... attachments) {
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.ATTACHMENTS.key(), List.of(attachments)));
    }

    private static List<ChatMessage> question() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(UserMessage.from("Summarize this"));
        return messages;
    }

    private static List<Content> contents(List<ChatMessage> messages) {
        return ((UserMessage) messages.get(messages.size() - 1)).contents();
    }

    private List<String> errors() {
        IData<List<?>> data = memory.getCurrentStep().getLatestData(MemoryKeys.ATTACHMENT_ERRORS.key());
        return data == null || data.getResult() == null ? List.of() : data.getResult().stream().map(String::valueOf).toList();
    }

    private void assertOnlyRangeText(List<ChatMessage> messages) {
        List<Content> contents = contents(messages);
        assertEquals(2, contents.size(), contents.toString());
        String text = assertInstanceOf(TextContent.class, contents.get(1)).text();
        assertTrue(text.contains("Page 3 line 1:") && text.contains("Page 5 line 1:"), text);
        assertFalse(text.contains("Page 2 line") || text.contains("Page 6 line"), text);
        assertTrue(contents.stream().noneMatch(PdfFileContent.class::isInstance));
    }

    private void assertNoWorkFiles() {
        try (Stream<Path> files = Files.list(workDir)) {
            assertEquals(0, files.count(), "a slice work file was left behind");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(PDDocument document) throws IOException {
        return new PDFTextStripper().getText(document).strip();
    }
}
