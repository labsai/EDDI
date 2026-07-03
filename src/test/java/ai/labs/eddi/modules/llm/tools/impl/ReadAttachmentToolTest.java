/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReadAttachmentTool}.
 */
class ReadAttachmentToolTest {

    private static final String CONV = "conv-1";

    private IAttachmentStore store;
    private ReadAttachmentTool tool;

    @BeforeEach
    void setUp() {
        store = mock(IAttachmentStore.class);
        tool = new ReadAttachmentTool(store, new AttachmentTextExtractor(10_000), CONV);
    }

    private static Attachment att(String ref, String name, String mime, long size) {
        return new Attachment(ref, name, mime, size, CONV);
    }

    // ==================== listAttachments ====================

    @Test
    void list_empty() {
        when(store.listAccessible(CONV)).thenReturn(List.of());
        assertTrue(tool.listAttachments().contains("No attachments"));
    }

    @Test
    void list_formatsEntries() {
        when(store.listAccessible(CONV)).thenReturn(List.of(
                att("r1", "report.pdf", "application/pdf", 2048),
                att("r2", "notes.txt", "text/plain", 12)));
        String out = tool.listAttachments();
        assertTrue(out.contains("report.pdf"));
        assertTrue(out.contains("application/pdf"));
        assertTrue(out.contains("notes.txt"));
        assertTrue(out.contains("r2"));
    }

    // ==================== readAttachment ====================

    @Test
    void read_byFileName_extractsText() throws Exception {
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "notes.txt", "text/plain", 5)));
        when(store.load("r1", CONV)).thenReturn("hello world".getBytes(StandardCharsets.UTF_8));

        String out = tool.readAttachment("notes.txt", 0);
        assertTrue(out.contains("hello world"));
    }

    @Test
    void read_byStorageRef_extractsText() throws Exception {
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "notes.txt", "text/plain", 5)));
        when(store.load("r1", CONV)).thenReturn("body".getBytes(StandardCharsets.UTF_8));

        assertTrue(tool.readAttachment("r1", 0).contains("body"));
    }

    @Test
    void read_caseInsensitiveFileName() throws Exception {
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "Notes.TXT", "text/plain", 5)));
        when(store.load("r1", CONV)).thenReturn("x".getBytes(StandardCharsets.UTF_8));
        assertTrue(tool.readAttachment("notes.txt", 0).contains("x"));
    }

    @Test
    void read_pdfPage() throws Exception {
        byte[] pdf = multiPagePdf("Alpha page", "Beta page");
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "doc.pdf", "application/pdf", pdf.length)));
        when(store.load("r1", CONV)).thenReturn(pdf);

        String out = tool.readAttachment("doc.pdf", 2);
        assertTrue(out.contains("Beta page"));
        assertFalse(out.contains("Alpha page"));
    }

    @Test
    void read_notFound() {
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "a.txt", "text/plain", 1)));
        assertTrue(tool.readAttachment("missing.txt", 0).contains("No attachment named"));
    }

    @Test
    void read_nonExtractableType_note() throws Exception {
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "pic.png", "image/png", 100)));
        when(store.load("r1", CONV)).thenReturn(new byte[]{1, 2, 3});
        assertTrue(tool.readAttachment("pic.png", 0).contains("no extractable text"));
    }

    @Test
    void read_loadDenied_error() throws Exception {
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "a.txt", "text/plain", 1)));
        when(store.load("r1", CONV)).thenThrow(new AttachmentStoreException("access denied"));
        assertTrue(tool.readAttachment("a.txt", 0).contains("Could not read attachment"));
    }

    @Test
    void read_emptyText_note() throws Exception {
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "empty.txt", "text/plain", 0)));
        when(store.load("r1", CONV)).thenReturn(new byte[0]);
        assertTrue(tool.readAttachment("empty.txt", 0).contains("no extractable text"));
    }

    @Test
    void read_blankRef_notFound() {
        when(store.listAccessible(CONV)).thenReturn(List.of(att("r1", "a.txt", "text/plain", 1)));
        assertTrue(tool.readAttachment("  ", 0).contains("No attachment named"));
    }

    // ==================== helper ====================

    private static byte[] multiPagePdf(String... pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                var page = new PDPage();
                doc.addPage(page);
                try (var cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(50, 700);
                    cs.showText(text);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
