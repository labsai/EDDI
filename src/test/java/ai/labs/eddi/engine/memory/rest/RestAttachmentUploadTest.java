/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAttachmentUpload}.
 */
class RestAttachmentUploadTest {

    private IAttachmentStore attachmentStore;
    private RestAttachmentUpload endpoint;

    @BeforeEach
    void setUp() {
        attachmentStore = mock(IAttachmentStore.class);
        endpoint = new RestAttachmentUpload(attachmentStore);
    }

    // ==================== Upload Tests ====================

    @Nested
    class UploadTests {

        @Test
        void shouldReturn400WhenNoFileProvided() {
            Response response = endpoint.uploadAttachment("conv-1", null, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        void shouldReturn400WhenFileNameIsNull() {
            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn(null);

            Response response = endpoint.uploadAttachment("conv-1", file, null);
            assertEquals(400, response.getStatus());
        }

        @Test
        void shouldReturn201OnSuccessfulUpload() throws Exception {
            var attachment = new Attachment(
                    "ref-123", "photo.png", "image/png", 42, "conv-1");
            when(attachmentStore.store(any(byte[].class), eq("image/png"),
                    eq("photo.png"), eq("conv-1"), isNull()))
                    .thenReturn(attachment);

            Path tempFile = Files.createTempFile("test-upload", ".png");
            Files.write(tempFile, new byte[42]);

            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn("photo.png");
            when(file.contentType()).thenReturn("image/png");
            when(file.uploadedFile()).thenReturn(tempFile);

            Response response = endpoint.uploadAttachment("conv-1", file, null);

            assertEquals(201, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (java.util.Map<String, Object>) response.getEntity();
            assertEquals("ref-123", body.get("storageRef"));
            assertEquals("photo.png", body.get("fileName"));
            assertEquals("image/png", body.get("mimeType"));
            assertEquals(42L, body.get("sizeBytes"));
            assertEquals("conv-1", body.get("conversationId"));

            Files.deleteIfExists(tempFile);
        }

        @Test
        void shouldDefaultMimeTypeToOctetStream() throws Exception {
            var attachment = new Attachment(
                    "ref-456", "data.bin",
                    "application/octet-stream", 10, "conv-1");
            when(attachmentStore.store(any(byte[].class),
                    eq("application/octet-stream"), eq("data.bin"),
                    eq("conv-1"), isNull()))
                    .thenReturn(attachment);

            Path tempFile = Files.createTempFile("test-upload", ".bin");
            Files.write(tempFile, new byte[10]);

            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn("data.bin");
            when(file.contentType()).thenReturn(null); // no content type
            when(file.uploadedFile()).thenReturn(tempFile);

            Response response = endpoint.uploadAttachment("conv-1", file, null);

            assertEquals(201, response.getStatus());
            verify(attachmentStore).store(any(byte[].class),
                    eq("application/octet-stream"), eq("data.bin"),
                    eq("conv-1"), isNull());

            Files.deleteIfExists(tempFile);
        }

        @Test
        void shouldReturn400WhenStoreRejects() throws Exception {
            when(attachmentStore.store(any(byte[].class), anyString(),
                    anyString(), anyString(), any()))
                    .thenThrow(new AttachmentStoreException(
                            "File too large"));

            Path tempFile = Files.createTempFile("test-upload", ".exe");
            Files.write(tempFile, new byte[100]);

            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn("malware.exe");
            when(file.contentType()).thenReturn("application/x-executable");
            when(file.uploadedFile()).thenReturn(tempFile);

            Response response = endpoint.uploadAttachment(
                    "conv-1", file, null);

            assertEquals(400, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (java.util.Map<String, Object>) response.getEntity();
            assertEquals("ATTACHMENT_REJECTED", body.get("code"));

            Files.deleteIfExists(tempFile);
        }

        @Test
        void shouldPassTenantIdToStore() throws Exception {
            var attachment = new Attachment(
                    "ref-789", "doc.pdf",
                    "application/pdf", 50, "conv-1");
            when(attachmentStore.store(any(byte[].class),
                    eq("application/pdf"), eq("doc.pdf"),
                    eq("conv-1"), eq("tenant-42")))
                    .thenReturn(attachment);

            Path tempFile = Files.createTempFile("test-upload", ".pdf");
            Files.write(tempFile, new byte[50]);

            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn("doc.pdf");
            when(file.contentType()).thenReturn("application/pdf");
            when(file.uploadedFile()).thenReturn(tempFile);

            Response response = endpoint.uploadAttachment(
                    "conv-1", file, "tenant-42");

            assertEquals(201, response.getStatus());
            verify(attachmentStore).store(any(byte[].class),
                    eq("application/pdf"), eq("doc.pdf"),
                    eq("conv-1"), eq("tenant-42"));

            Files.deleteIfExists(tempFile);
        }
    }

    // ==================== List Tests ====================

    @Nested
    class ListTests {

        @Test
        void shouldReturnAttachmentList() {
            var att1 = new Attachment(
                    "ref-1", "a.png", "image/png", 100, "conv-1");
            var att2 = new Attachment(
                    "ref-2", "b.jpg", "image/jpeg", 200, "conv-1");
            when(attachmentStore.listByConversation("conv-1"))
                    .thenReturn(List.of(att1, att2));

            Response response = endpoint.listAttachments("conv-1");

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var list = (List<Attachment>) response.getEntity();
            assertEquals(2, list.size());
        }

        @Test
        void shouldReturnEmptyListWhenNoAttachments() {
            when(attachmentStore.listByConversation("conv-empty"))
                    .thenReturn(List.of());

            Response response = endpoint.listAttachments("conv-empty");

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var list = (List<Attachment>) response.getEntity();
            assertTrue(list.isEmpty());
        }
    }

    // ==================== Delete Tests ====================

    @Nested
    class DeleteTests {

        @Test
        void shouldDeleteAttachmentsAndReturnCount() {
            when(attachmentStore.deleteByConversation("conv-1"))
                    .thenReturn(3L);

            Response response = endpoint.deleteAttachments("conv-1");

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (java.util.Map<String, Object>) response.getEntity();
            assertEquals("conv-1", body.get("conversationId"));
            assertEquals(3L, body.get("deletedCount"));
        }

        @Test
        void shouldReturnZeroWhenNoAttachmentsToDelete() {
            when(attachmentStore.deleteByConversation("conv-none"))
                    .thenReturn(0L);

            Response response = endpoint.deleteAttachments("conv-none");

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (java.util.Map<String, Object>) response.getEntity();
            assertEquals(0L, body.get("deletedCount"));
        }
    }
}
