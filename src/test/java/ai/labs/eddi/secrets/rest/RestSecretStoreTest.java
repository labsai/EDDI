package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link RestSecretStore}. Mocks the {@link ISecretProvider} and
 * {@link SecretResolver} to verify HTTP status codes, validation, and error
 * handling for all REST endpoints.
 */
class RestSecretStoreTest {

    private ISecretProvider secretProvider;
    private SecretResolver secretResolver;
    private RestSecretStore rest;

    @BeforeEach
    void setUp() {
        secretProvider = mock(ISecretProvider.class);
        secretResolver = mock(SecretResolver.class);
        when(secretProvider.isAvailable()).thenReturn(true);
        rest = new RestSecretStore(secretProvider, secretResolver);
    }

    // ─── storeSecret ───

    @Test
    void storeSecret_creates201WhenNew() throws Exception {
        when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        Response resp = rest.storeSecret("default", "myKey", new IRestSecretStore.SecretRequest("secret123", "desc", null));

        assertEquals(201, resp.getStatus());
        verify(secretProvider).store(any(SecretReference.class), eq("secret123"), eq("desc"), isNull());
    }

    @Test
    void storeSecret_returns200WhenUpdating() throws Exception {
        when(secretProvider.getMetadata(any())).thenReturn(new SecretMetadata("default", "myKey", Instant.now(), null, null, "cs", null, null));

        Response resp = rest.storeSecret("default", "myKey", new IRestSecretStore.SecretRequest("newVal", null, null));

        assertEquals(200, resp.getStatus());
        verify(secretResolver).invalidateCache(any(SecretReference.class));
    }

    @Test
    void storeSecret_returns400WhenValueEmpty() {
        Response resp = rest.storeSecret("default", "myKey", new IRestSecretStore.SecretRequest("", null, null));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void storeSecret_returns400WhenBodyNull() {
        Response resp = rest.storeSecret("default", "myKey", null);
        assertEquals(400, resp.getStatus());
    }

    @Test
    void storeSecret_returns400WhenKeyNameInvalid() {
        Response resp = rest.storeSecret("default", "../etc/passwd", new IRestSecretStore.SecretRequest("val", null, null));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void storeSecret_returns503WhenVaultUnavailable() {
        when(secretProvider.isAvailable()).thenReturn(false);
        Response resp = rest.storeSecret("default", "key", new IRestSecretStore.SecretRequest("val", null, null));
        assertEquals(503, resp.getStatus());
    }

    // ─── deleteSecret ───

    @Test
    void deleteSecret_returns204() throws Exception {
        Response resp = rest.deleteSecret("default", "myKey");
        assertEquals(204, resp.getStatus());
        verify(secretProvider).delete(any(SecretReference.class));
        verify(secretResolver).invalidateCache(any(SecretReference.class));
    }

    @Test
    void deleteSecret_returns404WhenNotFound() throws Exception {
        doThrow(new ISecretProvider.SecretNotFoundException("not found")).when(secretProvider).delete(any(SecretReference.class));
        Response resp = rest.deleteSecret("default", "myKey");
        assertEquals(404, resp.getStatus());
    }

    @Test
    void deleteSecret_returns400WhenInvalidId() {
        Response resp = rest.deleteSecret("default", "key with spaces");
        assertEquals(400, resp.getStatus());
        // Max length 128 chars
        Response resp2 = rest.deleteSecret("default", "a".repeat(200));
        assertEquals(400, resp2.getStatus());
    }

    // ─── getSecretMetadata ───

    @Test
    void getMetadata_returns200WithMetadata() throws Exception {
        Instant now = Instant.now();
        when(secretProvider.getMetadata(any())).thenReturn(new SecretMetadata("default", "apiKey", now, now, null, "cs123", "my key", List.of("*")));

        Response resp = rest.getSecretMetadata("default", "apiKey");
        assertEquals(200, resp.getStatus());
        assertNotNull(resp.getEntity());
    }

    @Test
    void getMetadata_returns404WhenNotFound() throws Exception {
        when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));
        Response resp = rest.getSecretMetadata("default", "missing");
        assertEquals(404, resp.getStatus());
    }

    // ─── listSecrets ───

    @Test
    @SuppressWarnings("unchecked")
    void listSecrets_returnsListForTenant() throws Exception {
        when(secretProvider.listKeys("default"))
                .thenReturn(List.of(new SecretMetadata("default", "key1", Instant.now(), null, null, "cs1", "desc1", List.of("*")),
                        new SecretMetadata("default", "key2", Instant.now(), null, null, "cs2", "desc2", List.of("agent1"))));

        Response resp = rest.listSecrets("default");
        assertEquals(200, resp.getStatus());
        List<SecretMetadata> list = (List<SecretMetadata>) resp.getEntity();
        assertEquals(2, list.size());
    }

    @Test
    void listSecrets_returnsEmptyForInvalidTenantId() {
        Response resp = rest.listSecrets("../bad");
        assertEquals(200, resp.getStatus());
        // Should return empty list, not error
    }

    // ─── healthCheck ───

    @Test
    @SuppressWarnings("unchecked")
    void healthCheck_returns200WhenUp() {
        Response resp = rest.healthCheck();
        assertEquals(200, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("UP", body.get("status"));
    }

    @Test
    void healthCheck_returns503WhenDown() {
        when(secretProvider.isAvailable()).thenReturn(false);
        Response resp = rest.healthCheck();
        assertEquals(503, resp.getStatus());
    }

    // ─── reference format ───

    @Test
    @SuppressWarnings("unchecked")
    void storeSecret_returnsShortFormRefForDefaultTenant() throws Exception {
        when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        Response resp = rest.storeSecret("default", "openaiKey", new IRestSecretStore.SecretRequest("sk-xxx", "OpenAI key", null));

        assertEquals(201, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("${eddivault:openaiKey}", body.get("reference"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeSecret_returnsFullFormRefForCustomTenant() throws Exception {
        when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        Response resp = rest.storeSecret("acme-corp", "dbPassword", new IRestSecretStore.SecretRequest("pass123", null, null));

        assertEquals(201, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("${eddivault:acme-corp/dbPassword}", body.get("reference"));
    }
}
