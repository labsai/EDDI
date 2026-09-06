/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.llm.capability.ModelCapabilityService;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor;
import dev.langchain4j.data.message.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.io.IOException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENTS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AttachmentForwarder}.
 */
class AttachmentForwarderTest {

    private IAttachmentStore store;
    private SafeHttpClient httpClient;
    private IConversationMemory memory;
    private IWritableConversationStep currentStep;
    private AttachmentForwarder forwarder;

    @BeforeEach
    void setUp() {
        store = mock(IAttachmentStore.class);
        httpClient = mock(SafeHttpClient.class);
        memory = mock(IConversationMemory.class);
        currentStep = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);
        when(memory.getConversationId()).thenReturn("conv-1");
        forwarder = newForwarder(10L * 1024 * 1024, 20L * 1024 * 1024);
    }

    /**
     * The registry backing the most recently built forwarder — for counter
     * assertions.
     */
    private SimpleMeterRegistry meterRegistry;

    private AttachmentForwarder newForwarder(long perFile, long aggregate) {
        var capability = new ModelCapabilityService(k -> Optional.empty());
        var extractor = new AttachmentTextExtractor(10_000);
        meterRegistry = new SimpleMeterRegistry();
        return new AttachmentForwarder(store, capability, extractor, httpClient,
                meterRegistry, perFile, aggregate);
    }

    // ==================== No-op cases ====================

    @Nested
    class NoOpCases {

        @Test
        void nullMessages() {
            forwarder.forward(null, memory, "openai", "gpt-4o");
        }

        @Test
        void emptyMessages() {
            List<ChatMessage> messages = new ArrayList<>();
            forwarder.forward(messages, memory, "openai", "gpt-4o");
            assertTrue(messages.isEmpty());
        }

        @Test
        void noAttachments() {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            List<ChatMessage> messages = messages(UserMessage.from("Hi"));
            forwarder.forward(messages, memory, "openai", "gpt-4o");
            assertEquals(1, messages.size());
        }

        @Test
        void noUserMessage() {
            mockAttachments(urlImage());
            List<ChatMessage> messages = messages(new SystemMessage("s"), AiMessage.from("a"));
            forwarder.forward(messages, memory, "openai", "gpt-4o");
            assertEquals(2, messages.size());
        }
    }

    // ==================== Earlier-turn attachments ====================

    /**
     * A file is inlined only on the turn it arrives. On later turns the model must
     * still be told the file exists, or it answers that nothing was ever shared.
     */
    @Nested
    class EarlierTurns {

        @Test
        void turnWithoutAttachments_notesFilesFromEarlierTurns() {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            mockPreviousTurnAttachments(storedPdf("ref-1", "Panera_Valuation.pdf"));
            List<ChatMessage> messages = messages(UserMessage.from("tl;dr of the pdf"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            String note = ((TextContent) enhanced.contents().get(1)).text();
            assertTrue(note.contains("Panera_Valuation.pdf"), note);
            assertTrue(note.contains("application/pdf"), note);
            assertTrue(note.contains("readAttachment"), note);
            // The document itself stays out of the prompt — only the pointer is added.
            assertFalse(note.contains("%PDF"), note);
            verifyNoInteractions(httpClient);
        }

        /**
         * Images are the exception to note-only: there is no OCR, so a note plus
         * readAttachment dead-ends in "no readable content" — the exact reply that made
         * an operator user believe upload was broken. A vision model gets the recent
         * images again as real image content.
         */
        @Test
        void earlierTurnImage_isReinlinedForAVisionModel() throws Exception {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            when(store.load(eq("img-1"), any())).thenReturn("pngbytes".getBytes());
            mockPreviousTurnAttachments(storedImage("img-1", "screenshot.png"));
            List<ChatMessage> messages = messages(UserMessage.from("what does the image say?"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size(), "the image must ride the message again, not a note");
            assertInstanceOf(ImageContent.class, enhanced.contents().get(1));
        }

        @Test
        void earlierTurnImage_staysANoteForANonVisionModel() {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            mockPreviousTurnAttachments(storedImage("img-1", "screenshot.png"));
            List<ChatMessage> messages = messages(UserMessage.from("what does the image say?"));

            forwarder.forward(messages, memory, "openai", "gpt-3.5-turbo");

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            String note = ((TextContent) enhanced.contents().get(1)).text();
            assertTrue(note.contains("screenshot.png"), note);
        }

        @Test
        void earlierTurnMixedFiles_reinlinesTheImageAndNotesTheRest() throws Exception {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            when(store.load(eq("img-1"), any())).thenReturn("pngbytes".getBytes());
            mockPreviousTurnAttachments(storedImage("img-1", "screenshot.png"), storedPdf("ref-1", "earlier.pdf"));
            List<ChatMessage> messages = messages(UserMessage.from("and the pdf?"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            // text + re-inlined image + note about the pdf
            assertEquals(3, enhanced.contents().size());
            assertInstanceOf(ImageContent.class, enhanced.contents().get(1));
            String note = ((TextContent) enhanced.contents().get(2)).text();
            assertTrue(note.contains("earlier.pdf"), note);
            assertFalse(note.contains("screenshot.png"), "a re-inlined image must not also be listed as unavailable: " + note);
        }

        /**
         * A failed store load on the re-inline path must NOT be counted or metered as a
         * re-inlined image: the failure note informs the model, but a permanently
         * missing blob would otherwise claim a successful re-inline (and bump the
         * counter) on every remaining turn.
         */
        @Test
        void earlierTurnImage_storeLoadFailure_isANoteAndNotCountedAsReinlined() throws Exception {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            when(store.load(eq("img-1"), any()))
                    .thenThrow(new IAttachmentStore.AttachmentStoreException("blob gone"));
            mockPreviousTurnAttachments(storedImage("img-1", "screenshot.png"));
            List<ChatMessage> messages = messages(UserMessage.from("the screenshot?"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            assertInstanceOf(TextContent.class, enhanced.contents().get(1), "the model must be told, via a note");
            assertTrue(((TextContent) enhanced.contents().get(1)).text().contains("could not be loaded"));
            assertEquals(0.0, meterRegistry.counter("eddi.attachment.reinlined").count(),
                    "a failed load is not a re-inline");
            assertEquals(1.0, meterRegistry.counter("eddi.attachment.errors").count());
        }

        /**
         * The override parameter this path explicitly threads through must be honored —
         * vision=OFF on a vision-capable model keeps the note.
         */
        @Test
        void earlierTurnImage_visionOverrideOff_staysANote() {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            mockPreviousTurnAttachments(storedImage("img-1", "screenshot.png"));
            List<ChatMessage> messages = messages(UserMessage.from("the screenshot?"));

            forwarder.forward(messages, memory, "openai", "gpt-4o",
                    ModelCapabilityService.Support.OFF,
                    ModelCapabilityService.Support.AUTO,
                    ModelCapabilityService.Support.AUTO);

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            String note = ((TextContent) enhanced.contents().get(1)).text();
            assertTrue(note.contains("screenshot.png"), note);
        }

        @Test
        void earlierTurnImages_aggregateCapSkipsTheOverflowWithANote() throws Exception {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            when(store.load(any(), any())).thenReturn("12345678".getBytes());
            mockPreviousTurnAttachments(storedImage("img-1", "first.png"), storedImage("img-2", "second.png"));
            List<ChatMessage> messages = messages(UserMessage.from("both screenshots?"));

            // Aggregate budget fits exactly one 8-byte image.
            var capped = newForwarder(1024, 10);
            capped.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            long images = enhanced.contents().stream().filter(c -> c instanceof ImageContent).count();
            assertEquals(1, images, "only what fits the aggregate budget is re-inlined");
            assertTrue(enhanced.contents().stream().anyMatch(
                    c -> c instanceof TextContent tc && tc.text().contains("second.png")),
                    "the skipped image must be reported, not silently dropped");
        }

        @Test
        void earlierTurnImages_reinliningIsCappedAtTheMostRecentThree() throws Exception {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            when(store.load(any(), any())).thenReturn("pngbytes".getBytes());
            mockPreviousTurnAttachments(
                    storedImage("img-1", "newest.png"), storedImage("img-2", "second.png"),
                    storedImage("img-3", "third.png"), storedImage("img-4", "oldest.png"));
            List<ChatMessage> messages = messages(UserMessage.from("the screenshots?"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            long images = enhanced.contents().stream().filter(c -> c instanceof ImageContent).count();
            assertEquals(3, images, "the gallery must not be re-paid whole every turn");
            String note = ((TextContent) enhanced.contents().get(enhanced.contents().size() - 1)).text();
            assertTrue(note.contains("oldest.png"), "the capped-out image must stay reachable via the note: " + note);
        }

        /**
         * A HITL resume re-enters the SAME step of a conversation reloaded from the
         * store, so "current step" does not imply "live objects" — the entries are
         * plain maps there. Casting instead of coercing dropped every attachment on a
         * resumed turn, silently un-attaching the file the user was approving.
         */
        @Test
        @SuppressWarnings({"unchecked", "rawtypes"})
        void currentStepReloadedFromTheStore_stillForwardsTheAttachment() throws Exception {
            byte[] pdf = tinyPdf("Resumed after approval");
            when(store.load(eq("ref-1"), any())).thenReturn(pdf);

            // The map form Jackson hands back for a persisted Attachment.
            Map<String, Object> persisted = new HashMap<>();
            persisted.put("storageRef", "ref-1");
            persisted.put("fileName", "report.pdf");
            persisted.put("mimeType", "application/pdf");
            persisted.put("sizeBytes", pdf.length);

            IData data = mock(IData.class);
            when(data.getResult()).thenReturn(List.of(persisted));
            when(currentStep.getData(ATTACHMENTS)).thenReturn(data);

            List<ChatMessage> messages = messages(UserMessage.from("Summarize it"));
            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size(), "the reloaded attachment must still reach the model");
        }

        @Test
        void turnWithoutAttachments_andNoEarlierFiles_leavesMessageUntouched() {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            when(memory.getPreviousSteps()).thenReturn(null);
            List<ChatMessage> messages = messages(UserMessage.from("Hi"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage unchanged = (UserMessage) messages.get(0);
            assertEquals(1, unchanged.contents().size());
        }

        @Test
        void turnWithItsOwnAttachments_doesNotAlsoAppendTheEarlierTurnNote() {
            mockAttachments(urlImage());
            mockPreviousTurnAttachments(storedPdf("ref-1", "earlier.pdf"));
            List<ChatMessage> messages = messages(UserMessage.from("Describe"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            // Original text + the image — no reminder note piled on top.
            assertEquals(2, enhanced.contents().size());
            assertInstanceOf(ImageContent.class, enhanced.contents().get(1));
        }

        @Test
        void noUserMessageToAnnotate_isANoOp() {
            when(currentStep.getData(ATTACHMENTS)).thenReturn(null);
            mockPreviousTurnAttachments(storedPdf("ref-1", "earlier.pdf"));
            List<ChatMessage> messages = messages(new SystemMessage("s"), AiMessage.from("a"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            assertEquals(2, messages.size());
            assertInstanceOf(SystemMessage.class, messages.get(0));
        }
    }

    // ==================== Images ====================

    @Nested
    class Images {

        @Test
        void imageUrlWithVisionAndUrlSupport_usesImageContentUrl() {
            mockAttachments(urlImage());
            List<ChatMessage> messages = messages(UserMessage.from("Describe"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertEquals(2, enhanced.contents().size());
            assertInstanceOf(ImageContent.class, enhanced.contents().get(1));
            verifyNoInteractions(httpClient); // URL not downloaded
        }

        @Test
        void imageUrlWithoutUrlSupport_downloadsAndInlines() throws Exception {
            mockAttachments(urlImage());
            mockDownload("imgbytes".getBytes());
            List<ChatMessage> messages = messages(UserMessage.from("Describe"));

            // gemini supports vision but not image-by-URL → download + inline
            forwarder.forward(messages, memory, "gemini", "gemini-2.0-flash");

            UserMessage enhanced = (UserMessage) messages.get(0);
            assertInstanceOf(ImageContent.class, enhanced.contents().get(1));
            verify(httpClient).sendValidated(any(), any());
        }

        @Test
        void base64Image_inlines() {
            Attachment att = new Attachment();
            att.setMimeType("image/jpeg");
            att.setBase64Data(Base64.getEncoder().encodeToString("img".getBytes()));
            mockAttachments(att);
            List<ChatMessage> messages = messages(UserMessage.from("What is this"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            assertInstanceOf(ImageContent.class, ((UserMessage) messages.get(0)).contents().get(1));
        }

        @Test
        void storedImage_loadsAndInlines() throws Exception {
            Attachment att = new Attachment();
            att.setMimeType("image/png");
            att.setStorageRef("ref-1");
            mockAttachments(att);
            when(store.load("ref-1", "conv-1")).thenReturn("png".getBytes());
            List<ChatMessage> messages = messages(UserMessage.from("look"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            assertInstanceOf(ImageContent.class, ((UserMessage) messages.get(0)).contents().get(1));
        }

        @Test
        void imageWithoutVision_addsNote() {
            Attachment att = new Attachment();
            att.setMimeType("image/png");
            att.setBase64Data(Base64.getEncoder().encodeToString("png".getBytes()));
            mockAttachments(att);
            List<ChatMessage> messages = messages(UserMessage.from("look"));

            forwarder.forward(messages, memory, "jlama", "any");

            Content c = ((UserMessage) messages.get(0)).contents().get(1);
            assertInstanceOf(TextContent.class, c);
            assertTrue(((TextContent) c).text().contains("not forwarded"));
        }
    }

    // ==================== PDF ====================

    @Nested
    class Pdfs {

        @Test
        void pdfWithDocumentSupport_usesPdfFileContent() {
            Attachment att = new Attachment();
            att.setMimeType("application/pdf");
            att.setBase64Data(Base64.getEncoder().encodeToString("%PDF-1.4 fake".getBytes()));
            mockAttachments(att);
            List<ChatMessage> messages = messages(UserMessage.from("summarize"));

            forwarder.forward(messages, memory, "anthropic", "claude-sonnet-4");

            assertInstanceOf(PdfFileContent.class, ((UserMessage) messages.get(0)).contents().get(1));
        }

        @Test
        void pdfWithoutDocumentSupport_extractsText() throws Exception {
            Attachment att = new Attachment();
            att.setMimeType("application/pdf");
            att.setBase64Data(Base64.getEncoder().encodeToString(tinyPdf("Hello from PDF")));
            att.setFileName("doc.pdf");
            mockAttachments(att);
            List<ChatMessage> messages = messages(UserMessage.from("summarize"));

            forwarder.forward(messages, memory, "openai", "gpt-4o");

            Content c = ((UserMessage) messages.get(0)).contents().get(1);
            assertInstanceOf(TextContent.class, c);
            assertTrue(((TextContent) c).text().contains("Hello from PDF"));
            // extracted text persisted for history stitching
            assertTrue(capturePersisted("attachments:extracts").stream()
                    .anyMatch(s -> s.contains("Hello from PDF")));
        }
    }

    // ==================== Text ====================

    @Test
    void textDocument_inlined() {
        Attachment att = new Attachment();
        att.setMimeType("text/plain");
        att.setFileName("notes.txt");
        att.setBase64Data(Base64.getEncoder().encodeToString("plain text body".getBytes(StandardCharsets.UTF_8)));
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("read"));

        forwarder.forward(messages, memory, "jlama", "any"); // no capability needed

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertInstanceOf(TextContent.class, c);
        assertTrue(((TextContent) c).text().contains("plain text body"));
    }

    // ==================== Audio ====================

    @Test
    void audioWithSupport_usesAudioContent() {
        Attachment att = audio();
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("transcribe"));

        forwarder.forward(messages, memory, "gemini", "gemini-2.0-flash");

        assertInstanceOf(AudioContent.class, ((UserMessage) messages.get(0)).contents().get(1));
    }

    @Test
    void audioWithoutSupport_addsNote() {
        mockAttachments(audio());
        List<ChatMessage> messages = messages(UserMessage.from("transcribe"));

        forwarder.forward(messages, memory, "openai", "gpt-4o"); // audio unsupported by default

        assertInstanceOf(TextContent.class, ((UserMessage) messages.get(0)).contents().get(1));
    }

    // ==================== Per-task overrides ====================

    @Test
    void visionOverrideOff_forcesImageNoteOnCapableModel() {
        Attachment att = new Attachment();
        att.setMimeType("image/png");
        att.setBase64Data(Base64.getEncoder().encodeToString("png".getBytes()));
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        // openai/gpt-4o has vision, but per-task OFF suppresses it
        forwarder.forward(messages, memory, "openai", "gpt-4o",
                ModelCapabilityService.Support.OFF,
                ModelCapabilityService.Support.AUTO,
                ModelCapabilityService.Support.AUTO);

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertInstanceOf(TextContent.class, c);
        assertTrue(((TextContent) c).text().contains("not forwarded"));
    }

    @Test
    void documentsOverrideOn_forcesNativePdfOnNonDocModel() {
        Attachment att = new Attachment();
        att.setMimeType("application/pdf");
        att.setBase64Data(Base64.getEncoder().encodeToString("%PDF-1.4".getBytes()));
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("summarize"));

        // openai defaults documents=off, but per-task ON forces native PdfFileContent
        forwarder.forward(messages, memory, "openai", "gpt-4o",
                ModelCapabilityService.Support.AUTO,
                ModelCapabilityService.Support.ON,
                ModelCapabilityService.Support.AUTO);

        assertInstanceOf(PdfFileContent.class, ((UserMessage) messages.get(0)).contents().get(1));
    }

    // ==================== Unsupported + caps ====================

    @Test
    void unsupportedType_addsNote() {
        Attachment att = new Attachment();
        att.setMimeType("application/zip");
        att.setBase64Data(Base64.getEncoder().encodeToString("zip".getBytes()));
        att.setFileName("a.zip");
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("open"));

        forwarder.forward(messages, memory, "openai", "gpt-4o");

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertTrue(((TextContent) c).text().contains("unsupported type"));
    }

    @Test
    void perFileCapExceeded_skipsWithNote() {
        var small = newForwarder(4, 20L * 1024 * 1024); // 4-byte per-file cap
        Attachment att = new Attachment();
        att.setMimeType("image/png");
        att.setBase64Data(Base64.getEncoder().encodeToString("way-too-big".getBytes()));
        att.setFileName("big.png");
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        small.forward(messages, memory, "openai", "gpt-4o");

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertInstanceOf(TextContent.class, c);
        assertTrue(((TextContent) c).text().contains("per-file forward limit"));
        assertTrue(capturePersisted("attachments:errors").stream()
                .anyMatch(s -> s.contains("per-file forward limit")));
    }

    @Test
    void storeLoadFailure_addsErrorNote() throws Exception {
        Attachment att = new Attachment();
        att.setMimeType("image/png");
        att.setStorageRef("missing");
        mockAttachments(att);
        when(store.load("missing", "conv-1"))
                .thenThrow(new IAttachmentStore.AttachmentStoreException("not found"));
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        forwarder.forward(messages, memory, "openai", "gpt-4o");

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertTrue(((TextContent) c).text().contains("could not be loaded"));
    }

    @Test
    void aggregateCapExceeded_skipsSecondWithNote() {
        var small = newForwarder(10L * 1024 * 1024, 4); // 4-byte aggregate budget
        Attachment a = new Attachment();
        a.setMimeType("image/png");
        a.setFileName("a.png");
        a.setBase64Data(Base64.getEncoder().encodeToString("abc".getBytes())); // 3 bytes
        Attachment b = new Attachment();
        b.setMimeType("image/png");
        b.setFileName("b.png");
        b.setBase64Data(Base64.getEncoder().encodeToString("de".getBytes())); // 2 bytes → 3+2 > 4
        mockAttachments(a, b);
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        small.forward(messages, memory, "openai", "gpt-4o");

        // a inlined (ImageContent), b noted (aggregate budget)
        UserMessage enhanced = (UserMessage) messages.get(0);
        assertInstanceOf(ImageContent.class, enhanced.contents().get(1));
        assertTrue(((TextContent) enhanced.contents().get(2)).text().contains("attachment budget"));
    }

    @Test
    void downloadNon200_addsNote() throws Exception {
        mockAttachments(urlImage());
        @SuppressWarnings("unchecked")
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(500);
        doReturn(resp).when(httpClient).sendValidated(any(), any());
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        forwarder.forward(messages, memory, "gemini", "gemini-2.0-flash"); // needs download

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertTrue(((TextContent) c).text().contains("download failed"));
    }

    @Test
    void downloadException_addsNote() throws Exception {
        mockAttachments(urlImage());
        doThrow(new IOException("boom")).when(httpClient).sendValidated(any(), any());
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        forwarder.forward(messages, memory, "gemini", "gemini-2.0-flash");

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertTrue(((TextContent) c).text().contains("could not be fetched"));
    }

    @Test
    void textWithNoExtractableContent_addsNote() {
        Attachment att = new Attachment();
        att.setMimeType("text/plain");
        att.setFileName("empty.txt");
        att.setBase64Data(""); // decodes to empty
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("read"));

        forwarder.forward(messages, memory, "openai", "gpt-4o");

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertTrue(((TextContent) c).text().contains("no extractable text"));
    }

    @Test
    void invalidBase64_addsNote() {
        Attachment att = new Attachment();
        att.setMimeType("image/png");
        att.setFileName("bad.png");
        att.setBase64Data("!!!not-base64!!!");
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        forwarder.forward(messages, memory, "openai", "gpt-4o");

        Content c = ((UserMessage) messages.get(0)).contents().get(1);
        assertTrue(((TextContent) c).text().contains("invalid base64"));
    }

    @Test
    void metrics_recordForwardedAndErrors() {
        var registry = new SimpleMeterRegistry();
        var f = new AttachmentForwarder(store, new ModelCapabilityService(k -> Optional.empty()),
                new AttachmentTextExtractor(10_000), httpClient, registry, 10L * 1024 * 1024, 20L * 1024 * 1024);
        Attachment ok = new Attachment();
        ok.setMimeType("image/png");
        ok.setBase64Data(Base64.getEncoder().encodeToString("png".getBytes()));
        Attachment bad = new Attachment();
        bad.setMimeType("image/png"); // no source → error, no content
        mockAttachments(ok, bad);
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        f.forward(messages, memory, "openai", "gpt-4o");

        assertEquals(1.0, registry.counter("eddi.attachment.forwarded").count());
        assertEquals(1.0, registry.counter("eddi.attachment.errors").count());
    }

    @Test
    void secondInvocation_withPersistedExtractsErrors_stillForwards() {
        // Regression: a prior forwarder pass in the same step persisted the
        // attachments:extracts / attachments:errors keys. A prefix read of
        // "attachments" would return one of those List<String> entries and forward
        // nothing; the exact-match read must still find the List<Attachment>.
        var realMemory = new ConversationMemory("agent-1", 1, "user-1");
        var step = realMemory.getCurrentStep();
        Attachment att = new Attachment();
        att.setMimeType("image/png");
        att.setBase64Data(Base64.getEncoder().encodeToString("png".getBytes()));
        step.storeData(new Data<>(ATTACHMENTS.key(), List.of(att)));
        // Inserted AFTER attachments — this is what a prefix reverse-scan would return
        // first.
        step.storeData(new Data<>(
                MemoryKeys.ATTACHMENT_ERRORS.key(), List.of("earlier error")));
        step.storeData(new Data<>(
                MemoryKeys.ATTACHMENT_EXTRACTS.key(), List.of("doc: earlier extract")));

        List<ChatMessage> messages = messages(UserMessage.from("look"));
        forwarder.forward(messages, realMemory, "openai", "gpt-4o");

        assertInstanceOf(ImageContent.class, ((UserMessage) messages.get(0)).contents().get(1));
    }

    @Test
    void noContentSource_skipped() {
        Attachment att = new Attachment(); // NONE
        att.setMimeType("image/png");
        mockAttachments(att);
        List<ChatMessage> messages = messages(UserMessage.from("look"));

        forwarder.forward(messages, memory, "openai", "gpt-4o");

        // nothing added — only original text remains
        assertEquals(1, ((UserMessage) messages.get(0)).contents().size());
    }

    // ==================== Helpers ====================

    private List<String> capturePersisted(String key) {
        ArgumentCaptor<IData> captor = ArgumentCaptor.forClass(IData.class);
        verify(currentStep, atLeast(0)).storeData(captor.capture());
        for (IData d : captor.getAllValues()) {
            if (key.equals(d.getKey()) && d.getResult() instanceof List<?> list) {
                List<String> out = new ArrayList<>();
                list.forEach(o -> out.add(String.valueOf(o)));
                return out;
            }
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private void mockDownload(byte[] bytes) throws Exception {
        HttpResponse<byte[]> resp = mock(HttpResponse.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(bytes);
        doReturn(resp).when(httpClient).sendValidated(any(), any());
    }

    private static Attachment urlImage() {
        Attachment att = new Attachment();
        att.setMimeType("image/png");
        att.setUrl("https://example.com/photo.png");
        att.setFileName("photo.png");
        return att;
    }

    private static Attachment audio() {
        Attachment att = new Attachment();
        att.setMimeType("audio/mpeg");
        att.setFileName("clip.mp3");
        att.setBase64Data(Base64.getEncoder().encodeToString("audio".getBytes()));
        return att;
    }

    private static List<ChatMessage> messages(ChatMessage... m) {
        List<ChatMessage> list = new ArrayList<>();
        for (ChatMessage cm : m)
            list.add(cm);
        return list;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockAttachments(Attachment... attachments) {
        IData data = mock(IData.class);
        when(data.getResult()).thenReturn(List.of(attachments));
        when(currentStep.getData(ATTACHMENTS)).thenReturn(data);
    }

    /**
     * Stub {@code memory.getPreviousSteps()} with a single earlier step carrying
     * the given attachments.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void mockPreviousTurnAttachments(Attachment... attachments) {
        IConversationMemory.IConversationStep step = mock(IConversationMemory.IConversationStep.class);
        IData data = mock(IData.class);
        when(data.getResult()).thenReturn(List.of(attachments));
        when(step.getData(ATTACHMENTS)).thenReturn(data);

        IConversationMemory.IConversationStepStack stack = mock(IConversationMemory.IConversationStepStack.class);
        when(stack.size()).thenReturn(1);
        when(stack.get(0)).thenReturn(step);
        when(memory.getPreviousSteps()).thenReturn(stack);
    }

    private static Attachment storedPdf(String ref, String fileName) {
        Attachment att = new Attachment();
        att.setStorageRef(ref);
        att.setFileName(fileName);
        att.setMimeType("application/pdf");
        return att;
    }

    private static Attachment storedImage(String ref, String fileName) {
        Attachment att = new Attachment();
        att.setStorageRef(ref);
        att.setFileName(fileName);
        att.setMimeType("image/png");
        return att;
    }

    private static byte[] tinyPdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
