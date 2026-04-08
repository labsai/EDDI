package ai.labs.eddi.engine.memory.rest;

import ai.labs.eddi.engine.memory.IAttachmentStorage;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestAttachmentUpload}.
 */
class RestAttachmentUploadTest {

    private Instance<IAttachmentStorage> storageInstance;
    private IAttachmentStorage storage;
    private RestAttachmentUpload endpoint;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        storageInstance = mock(Instance.class);
        storage = mock(IAttachmentStorage.class);
        endpoint = new RestAttachmentUpload(storageInstance);
    }

    @Test
    void shouldReturn503WhenNoStorageConfigured() {
        when(storageInstance.isResolvable()).thenReturn(false);

        FileUpload file = mockFileUpload("test.jpg", "image/jpeg", 100);
        Response response = endpoint.uploadAttachment("conv-1", file);

        assertEquals(503, response.getStatus());
    }

    @Test
    void shouldReturn400WhenNoFileProvided() {
        when(storageInstance.isResolvable()).thenReturn(true);

        Response response = endpoint.uploadAttachment("conv-1", null);

        assertEquals(400, response.getStatus());
    }

    @Test
    void shouldReturn400WhenFileNameIsNull() {
        when(storageInstance.isResolvable()).thenReturn(true);

        FileUpload file = mock(FileUpload.class);
        when(file.fileName()).thenReturn(null);

        Response response = endpoint.uploadAttachment("conv-1", file);

        assertEquals(400, response.getStatus());
    }

    @Test
    void shouldReturn201OnSuccessfulUpload() throws Exception {
        when(storageInstance.isResolvable()).thenReturn(true);
        when(storageInstance.get()).thenReturn(storage);
        when(storage.store(eq("conv-1"), eq("photo.png"), eq("image/png"), any(), eq(42L)))
                .thenReturn("gridfs://abc123");

        // Create a real temp file so Files.newInputStream works
        Path tempFile = Files.createTempFile("test-upload", ".png");
        Files.write(tempFile, new byte[42]);

        FileUpload file = mock(FileUpload.class);
        when(file.fileName()).thenReturn("photo.png");
        when(file.contentType()).thenReturn("image/png");
        when(file.size()).thenReturn(42L);
        when(file.uploadedFile()).thenReturn(tempFile);

        Response response = endpoint.uploadAttachment("conv-1", file);

        assertEquals(201, response.getStatus());
        @SuppressWarnings("unchecked")
        var body = (java.util.Map<String, Object>) response.getEntity();
        assertEquals("gridfs://abc123", body.get("storageRef"));
        assertEquals("photo.png", body.get("fileName"));
        assertEquals("image/png", body.get("mimeType"));
        assertEquals(42L, body.get("sizeBytes"));

        // Cleanup
        Files.deleteIfExists(tempFile);
    }

    @Test
    void shouldDefaultMimeTypeToOctetStream() throws Exception {
        when(storageInstance.isResolvable()).thenReturn(true);
        when(storageInstance.get()).thenReturn(storage);
        when(storage.store(eq("conv-1"), eq("data.bin"), eq("application/octet-stream"), any(), eq(10L)))
                .thenReturn("pg://uuid-123");

        Path tempFile = Files.createTempFile("test-upload", ".bin");
        Files.write(tempFile, new byte[10]);

        FileUpload file = mock(FileUpload.class);
        when(file.fileName()).thenReturn("data.bin");
        when(file.contentType()).thenReturn(null); // no content type
        when(file.size()).thenReturn(10L);
        when(file.uploadedFile()).thenReturn(tempFile);

        Response response = endpoint.uploadAttachment("conv-1", file);

        assertEquals(201, response.getStatus());
        verify(storage).store(eq("conv-1"), eq("data.bin"), eq("application/octet-stream"), any(), eq(10L));

        Files.deleteIfExists(tempFile);
    }

    private FileUpload mockFileUpload(String fileName, String contentType, long size) {
        FileUpload file = mock(FileUpload.class);
        when(file.fileName()).thenReturn(fileName);
        when(file.contentType()).thenReturn(contentType);
        when(file.size()).thenReturn(size);
        return file;
    }
}
