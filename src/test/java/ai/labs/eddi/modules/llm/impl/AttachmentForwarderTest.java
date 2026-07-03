/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Attachment;
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
import java.util.List;
import java.util.Optional;

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

    private AttachmentForwarder newForwarder(long perFile, long aggregate) {
        var capability = new ModelCapabilityService(k -> Optional.empty());
        var extractor = new AttachmentTextExtractor(10_000);
        return new AttachmentForwarder(store, capability, extractor, httpClient,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), perFile, aggregate);
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
            when(currentStep.getLatestData(ATTACHMENTS)).thenReturn(null);
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
        doThrow(new java.io.IOException("boom")).when(httpClient).sendValidated(any(), any());
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
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
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
        when(currentStep.getLatestData(ATTACHMENTS)).thenReturn(data);
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
