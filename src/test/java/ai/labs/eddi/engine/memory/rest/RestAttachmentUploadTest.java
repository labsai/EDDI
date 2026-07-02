/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.IAttachmentStore.Attachment;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.context.ManagedExecutor;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAttachmentUpload}.
 */
class RestAttachmentUploadTest {

    private static final long MAX_UPLOAD_BYTES = 20 * 1024 * 1024; // 20MB
    private static final long MAX_FORWARD_BYTES = 10 * 1024 * 1024; // 10MB

    private IAttachmentStore attachmentStore;
    private ManagedExecutor managedExecutor;
    private RestAttachmentUpload endpoint;

    @BeforeEach
    void setUp() {
        attachmentStore = mock(IAttachmentStore.class);
        managedExecutor = ManagedExecutor.builder().build();
        endpoint = new RestAttachmentUpload(attachmentStore, managedExecutor, MAX_UPLOAD_BYTES, MAX_FORWARD_BYTES);
    }

    /**
     * Helper: calls an async endpoint and captures the resumed Response.
     */
    private Response captureAsync(AsyncEndpointCall call) throws Exception {
        AsyncResponse asyncResponse = mock(AsyncResponse.class);
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        CountDownLatch latch = new CountDownLatch(1);

        doAnswer(inv -> {
            latch.countDown();
            return null;
        }).when(asyncResponse).resume(captor.capture());

        call.invoke(asyncResponse);
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Async response not resumed within timeout");
        return captor.getValue();
    }

    @FunctionalInterface
    interface AsyncEndpointCall {
        void invoke(AsyncResponse asyncResponse) throws Exception;
    }

    // ==================== Upload Tests ====================

    @Nested
    class UploadTests {

        @Test
        void shouldReturn400WhenNoFileProvided() throws Exception {
            Response response = captureAsync(ar -> endpoint.uploadAttachment("conv-1", null, null, ar));
            assertEquals(400, response.getStatus());
        }

        @Test
        void shouldReturn400WhenFileNameIsNull() throws Exception {
            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn(null);

            Response response = captureAsync(ar -> endpoint.uploadAttachment("conv-1", file, null, ar));
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

            Response response = captureAsync(ar -> endpoint.uploadAttachment("conv-1", file, null, ar));

            assertEquals(201, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ref-123", body.get("storageRef"));
            assertEquals("photo.png", body.get("fileName"));
            assertEquals("image/png", body.get("mimeType"));
            assertEquals(42L, body.get("sizeBytes"));
            assertEquals("conv-1", body.get("conversationId"));
            assertEquals(true, body.get("forwardableInline"));

            Files.deleteIfExists(tempFile);
        }

        @Test
        void shouldMarkOversizeUploadNotForwardableInline() throws Exception {
            // store reports an 11 MB blob (> 10 MB forward cap) though the temp file is
            // tiny
            var attachment = new Attachment(
                    "ref-big", "huge.png", "image/png", 11L * 1024 * 1024, "conv-1");
            when(attachmentStore.store(any(byte[].class), eq("image/png"),
                    eq("huge.png"), eq("conv-1"), isNull()))
                    .thenReturn(attachment);

            Path tempFile = Files.createTempFile("test-upload", ".png");
            Files.write(tempFile, new byte[10]);

            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn("huge.png");
            when(file.contentType()).thenReturn("image/png");
            when(file.uploadedFile()).thenReturn(tempFile);

            Response response = captureAsync(ar -> endpoint.uploadAttachment("conv-1", file, null, ar));

            assertEquals(201, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals(false, body.get("forwardableInline"));

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

            Response response = captureAsync(ar -> endpoint.uploadAttachment("conv-1", file, null, ar));

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

            Response response = captureAsync(ar -> endpoint.uploadAttachment("conv-1", file, null, ar));

            assertEquals(400, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ATTACHMENT_REJECTED", body.get("code"));

            Files.deleteIfExists(tempFile);
        }

        @Test
        void shouldPassSanitizedTenantIdToStore() throws Exception {
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

            Response response = captureAsync(ar -> endpoint.uploadAttachment("conv-1", file, "tenant-42", ar));

            assertEquals(201, response.getStatus());
            verify(attachmentStore).store(any(byte[].class),
                    eq("application/pdf"), eq("doc.pdf"),
                    eq("conv-1"), eq("tenant-42"));

            Files.deleteIfExists(tempFile);
        }

        @Test
        void shouldRejectInvalidTenantId() throws Exception {
            var attachment = new Attachment(
                    "ref-999", "file.txt",
                    "text/plain", 5, "conv-1");
            // Expect null tenantId (sanitized away)
            when(attachmentStore.store(any(byte[].class),
                    eq("text/plain"), eq("file.txt"),
                    eq("conv-1"), isNull()))
                    .thenReturn(attachment);

            Path tempFile = Files.createTempFile("test-upload", ".txt");
            Files.write(tempFile, new byte[5]);

            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn("file.txt");
            when(file.contentType()).thenReturn("text/plain");
            when(file.uploadedFile()).thenReturn(tempFile);

            // Invalid tenantId (contains injection characters)
            Response response = captureAsync(ar -> endpoint.uploadAttachment(
                    "conv-1", file, "'; DROP TABLE--", ar));

            assertEquals(201, response.getStatus());
            // tenantId should have been sanitized to null
            verify(attachmentStore).store(any(byte[].class),
                    eq("text/plain"), eq("file.txt"),
                    eq("conv-1"), isNull());

            Files.deleteIfExists(tempFile);
        }

        @Test
        void shouldReturn500WhenFileReadFails() throws Exception {
            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn("broken.dat");
            when(file.contentType()).thenReturn("application/octet-stream");
            // Return a path that does not exist → IOException
            when(file.uploadedFile()).thenReturn(
                    Path.of("nonexistent-path-" + System.nanoTime()));

            Response response = captureAsync(ar -> endpoint.uploadAttachment("conv-1", file, null, ar));

            assertEquals(500, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ATTACHMENT_UPLOAD_FAILED", body.get("code"));
        }

        @Test
        void shouldReturn400WhenFileTooLarge() throws Exception {
            // Create endpoint with very small max size
            var smallEndpoint = new RestAttachmentUpload(attachmentStore, managedExecutor, 100, MAX_FORWARD_BYTES);

            Path tempFile = Files.createTempFile("test-large", ".bin");
            Files.write(tempFile, new byte[200]); // Exceeds 100 byte limit

            FileUpload file = mock(FileUpload.class);
            when(file.fileName()).thenReturn("large.bin");
            when(file.contentType()).thenReturn("application/octet-stream");
            when(file.uploadedFile()).thenReturn(tempFile);

            Response response = captureAsync(ar -> smallEndpoint.uploadAttachment("conv-1", file, null, ar));

            assertEquals(400, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ATTACHMENT_TOO_LARGE", body.get("code"));

            // Verify store was never called
            verifyNoInteractions(attachmentStore);

            Files.deleteIfExists(tempFile);
        }
    }

    // ==================== List Tests ====================

    @Nested
    class ListTests {

        @Test
        void shouldReturnAttachmentList() throws Exception {
            var att1 = new Attachment(
                    "ref-1", "a.png", "image/png", 100, "conv-1");
            var att2 = new Attachment(
                    "ref-2", "b.jpg", "image/jpeg", 200, "conv-1");
            when(attachmentStore.listByConversation("conv-1"))
                    .thenReturn(List.of(att1, att2));

            Response response = captureAsync(ar -> endpoint.listAttachments("conv-1", ar));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var list = (List<Attachment>) response.getEntity();
            assertEquals(2, list.size());
        }

        @Test
        void shouldReturnEmptyListWhenNoAttachments() throws Exception {
            when(attachmentStore.listByConversation("conv-empty"))
                    .thenReturn(List.of());

            Response response = captureAsync(ar -> endpoint.listAttachments("conv-empty", ar));

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
        void shouldDeleteAttachmentsAndReturnCount() throws Exception {
            when(attachmentStore.deleteByConversation("conv-1"))
                    .thenReturn(3L);

            Response response = captureAsync(ar -> endpoint.deleteAttachments("conv-1", ar));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("conv-1", body.get("conversationId"));
            assertEquals(3L, body.get("deletedCount"));
        }

        @Test
        void shouldReturnZeroWhenNoAttachmentsToDelete() throws Exception {
            when(attachmentStore.deleteByConversation("conv-none"))
                    .thenReturn(0L);

            Response response = captureAsync(ar -> endpoint.deleteAttachments("conv-none", ar));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals(0L, body.get("deletedCount"));
        }
    }

    // ==================== Download Tests ====================

    @Nested
    class DownloadTests {

        @Test
        void shouldStreamBytesWithHeaders() throws Exception {
            var meta = new Attachment("ref-1", "doc.pdf", "application/pdf", 4, "conv-1");
            when(attachmentStore.getMetadata("ref-1", "conv-1")).thenReturn(meta);
            when(attachmentStore.load("ref-1", "conv-1")).thenReturn(new byte[]{1, 2, 3, 4});

            Response response = captureAsync(ar -> endpoint.downloadAttachment("conv-1", "ref-1", ar));

            assertEquals(200, response.getStatus());
            assertInstanceOf(byte[].class, response.getEntity());
            assertEquals("application/pdf", response.getHeaderString("Content-Type"));
            assertTrue(response.getHeaderString("Content-Disposition").contains("doc.pdf"));
        }

        @Test
        void shouldReturn404WhenNotFound() throws Exception {
            when(attachmentStore.getMetadata("missing", "conv-1"))
                    .thenThrow(new AttachmentStoreException("Attachment not found: missing"));

            Response response = captureAsync(ar -> endpoint.downloadAttachment("conv-1", "missing", ar));

            assertEquals(404, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ATTACHMENT_NOT_FOUND", body.get("code"));
        }

        @Test
        void shouldReturn403WhenDenied() throws Exception {
            when(attachmentStore.getMetadata("ref-1", "conv-other"))
                    .thenThrow(new AttachmentStoreException(
                            "Cross-conversation access denied: attachment belongs to 'conv-1', requested from 'conv-other'"));

            Response response = captureAsync(ar -> endpoint.downloadAttachment("conv-other", "ref-1", ar));

            assertEquals(403, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ATTACHMENT_ACCESS_DENIED", body.get("code"));
        }

        @Test
        void shouldSanitizeContentDispositionFilename() throws Exception {
            var meta = new Attachment("ref-1", "bad\"name\r\n.png", "image/png", 2, "conv-1");
            when(attachmentStore.getMetadata("ref-1", "conv-1")).thenReturn(meta);
            when(attachmentStore.load("ref-1", "conv-1")).thenReturn(new byte[]{1, 2});

            Response response = captureAsync(ar -> endpoint.downloadAttachment("conv-1", "ref-1", ar));

            String cd = response.getHeaderString("Content-Disposition");
            assertFalse(cd.contains("\"" + "name"), "quotes must be stripped from filename");
            assertFalse(cd.contains("\r") || cd.contains("\n"), "CR/LF must be stripped");
        }
    }

    // ==================== Delete-One Tests ====================

    @Nested
    class DeleteOneTests {

        @Test
        void shouldDeleteAndReturnTrue() throws Exception {
            when(attachmentStore.delete("ref-1", "conv-1")).thenReturn(true);

            Response response = captureAsync(ar -> endpoint.deleteAttachment("conv-1", "ref-1", ar));

            assertEquals(200, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals(true, body.get("deleted"));
        }

        @Test
        void shouldReturn404WhenNotFound() throws Exception {
            when(attachmentStore.delete("missing", "conv-1")).thenReturn(false);

            Response response = captureAsync(ar -> endpoint.deleteAttachment("conv-1", "missing", ar));

            assertEquals(404, response.getStatus());
        }

        @Test
        void shouldReturn403WhenNotOwner() throws Exception {
            when(attachmentStore.delete("ref-1", "conv-other"))
                    .thenThrow(new AttachmentStoreException(
                            "Delete denied: attachment belongs to 'conv-1', requested from 'conv-other'"));

            Response response = captureAsync(ar -> endpoint.deleteAttachment("conv-other", "ref-1", ar));

            assertEquals(403, response.getStatus());
            @SuppressWarnings("unchecked")
            var body = (Map<String, Object>) response.getEntity();
            assertEquals("ATTACHMENT_ACCESS_DENIED", body.get("code"));
        }
    }
}
