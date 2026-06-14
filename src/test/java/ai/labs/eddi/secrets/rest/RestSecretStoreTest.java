/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.ws.rs.core.Response;
import ai.labs.eddi.secrets.impl.VaultSecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
    void storeSecret_invalidatesCacheOnNewCreation() throws Exception {
        // Bug regression: invalidateCache must fire on NEW creation, not just updates.
        // A model may have been cached with a failed (passthrough) vault reference,
        // so creating the secret must evict that stale cache entry.
        when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        rest.storeSecret("default", "myKey", new IRestSecretStore.SecretRequest("secret123", null, null));

        verify(secretResolver).invalidateCache(any(SecretReference.class));
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
    void listSecrets_returns400ForInvalidTenantId() {
        Response resp = rest.listSecrets("../bad");
        assertEquals(400, resp.getStatus());
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

    // ─── rotateDek ───

    @Test
    @SuppressWarnings("unchecked")
    void rotateDek_returns200WithCount() throws Exception {
        when(secretProvider.rotateDek("default")).thenReturn(3);

        Response resp = rest.rotateDek("default");
        assertEquals(200, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals(3, body.get("secretsReEncrypted"));
        verify(secretResolver).invalidateAll();
    }

    @Test
    void rotateDek_returns400ForInvalidTenantId() {
        Response resp = rest.rotateDek("../bad");
        assertEquals(400, resp.getStatus());
    }

    @Test
    void rotateDek_returns500OnFailure() throws Exception {
        when(secretProvider.rotateDek("default")).thenThrow(new ISecretProvider.SecretProviderException("No DEK found"));
        Response resp = rest.rotateDek("default");
        assertEquals(500, resp.getStatus());
    }

    @Test
    void rotateDek_returns503WhenVaultUnavailable() {
        when(secretProvider.isAvailable()).thenReturn(false);
        Response resp = rest.rotateDek("default");
        assertEquals(503, resp.getStatus());
    }

    // ─── rotateKek ───

    @Test
    void rotateKek_returns400WhenBodyNull() {
        Response resp = rest.rotateKek(null);
        assertEquals(400, resp.getStatus());
    }

    @Test
    void rotateKek_returns400WhenKeysEmpty() {
        Response resp = rest.rotateKek(new IRestSecretStore.KekRotationRequest("", "newkey"));
        assertEquals(400, resp.getStatus());
    }

    @Test
    void rotateKek_returns400WhenNewKeyTooShort() {
        Response resp = rest.rotateKek(new IRestSecretStore.KekRotationRequest("oldkey", "short"));
        assertEquals(400, resp.getStatus());
    }

    // ─── reference format ───

    @Test
    @SuppressWarnings("unchecked")
    void storeSecret_returnsShortFormRefForDefaultTenant() throws Exception {
        when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        Response resp = rest.storeSecret("default", "openaiKey", new IRestSecretStore.SecretRequest("sk-xxx", "OpenAI key", null));

        assertEquals(201, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("${vault:openaiKey}", body.get("reference"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void storeSecret_returnsFullFormRefForCustomTenant() throws Exception {
        when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        Response resp = rest.storeSecret("acme-corp", "dbPassword", new IRestSecretStore.SecretRequest("pass123", null, null));

        assertEquals(201, resp.getStatus());
        Map<String, Object> body = (Map<String, Object>) resp.getEntity();
        assertEquals("${vault:acme-corp/dbPassword}", body.get("reference"));
    }

    // ─── storeSecret — error paths ───

    @Nested
    @DisplayName("storeSecret error paths")
    class StoreSecretErrors {

        @Test
        @DisplayName("should return 500 when store throws SecretProviderException")
        void returns500OnProviderException() throws Exception {
            when(secretProvider.getMetadata(any()))
                    .thenThrow(new ISecretProvider.SecretNotFoundException("not found"));
            doThrow(new ISecretProvider.SecretProviderException("IO error"))
                    .when(secretProvider).store(any(), any(), any(), any());

            Response resp = rest.storeSecret("default", "myKey",
                    new IRestSecretStore.SecretRequest("secret123", "desc", null));

            assertEquals(500, resp.getStatus());
        }

        @Test
        @DisplayName("should return 400 when tenantId is null")
        void returns400ForNullTenantId() {
            Response resp = rest.storeSecret(null, "key",
                    new IRestSecretStore.SecretRequest("val", null, null));
            assertEquals(400, resp.getStatus());
        }

        @Test
        @DisplayName("should return 400 when value is null in body")
        void returns400ForNullValue() {
            Response resp = rest.storeSecret("default", "key",
                    new IRestSecretStore.SecretRequest(null, null, null));
            assertEquals(400, resp.getStatus());
        }
    }

    // ─── deleteSecret — additional error paths ───

    @Nested
    @DisplayName("deleteSecret additional paths")
    class DeleteSecretAdditional {

        @Test
        @DisplayName("should return 503 when vault is unavailable")
        void returns503WhenUnavailable() {
            when(secretProvider.isAvailable()).thenReturn(false);
            Response resp = rest.deleteSecret("default", "myKey");
            assertEquals(503, resp.getStatus());
        }

        @Test
        @DisplayName("should return 500 when delete throws SecretProviderException")
        void returns500OnProviderException() throws Exception {
            doThrow(new ISecretProvider.SecretProviderException("IO error"))
                    .when(secretProvider).delete(any());

            Response resp = rest.deleteSecret("default", "myKey");
            assertEquals(500, resp.getStatus());
        }
    }

    // ─── getSecretMetadata — additional paths ───

    @Nested
    @DisplayName("getSecretMetadata additional paths")
    class GetMetadataAdditional {

        @Test
        @DisplayName("should return 503 when vault is unavailable")
        void returns503WhenUnavailable() {
            when(secretProvider.isAvailable()).thenReturn(false);
            Response resp = rest.getSecretMetadata("default", "myKey");
            assertEquals(503, resp.getStatus());
        }

        @Test
        @DisplayName("should return 500 when getMetadata throws SecretProviderException")
        void returns500OnProviderException() throws Exception {
            when(secretProvider.getMetadata(any()))
                    .thenThrow(new ISecretProvider.SecretProviderException("corrupt data"));

            Response resp = rest.getSecretMetadata("default", "myKey");
            assertEquals(500, resp.getStatus());
        }

        @Test
        @DisplayName("should return 400 for invalid tenantId")
        void returns400ForInvalidTenantId() {
            Response resp = rest.getSecretMetadata("../evil", "myKey");
            assertEquals(400, resp.getStatus());
        }

        @Test
        @DisplayName("should return 400 for invalid keyName")
        void returns400ForInvalidKeyName() {
            Response resp = rest.getSecretMetadata("default", "key with spaces");
            assertEquals(400, resp.getStatus());
        }
    }

    // ─── listSecrets — additional paths ───

    @Nested
    @DisplayName("listSecrets additional paths")
    class ListSecretsAdditional {

        @Test
        @DisplayName("should return 503 when vault is unavailable")
        void returns503WhenUnavailable() {
            when(secretProvider.isAvailable()).thenReturn(false);
            Response resp = rest.listSecrets("default");
            assertEquals(503, resp.getStatus());
        }

        @Test
        @DisplayName("should return 500 when listKeys throws SecretProviderException")
        void returns500OnProviderException() throws Exception {
            when(secretProvider.listKeys("default"))
                    .thenThrow(new ISecretProvider.SecretProviderException("DB error"));

            Response resp = rest.listSecrets("default");
            assertEquals(500, resp.getStatus());
        }
    }

    // ─── rotateKek — additional paths ───

    @Nested
    @DisplayName("rotateKek additional paths")
    class RotateKekAdditional {

        @Test
        @DisplayName("should return 503 when vault is unavailable")
        void returns503WhenUnavailable() {
            when(secretProvider.isAvailable()).thenReturn(false);
            Response resp = rest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("oldkey123", "newkey12345678"));
            assertEquals(503, resp.getStatus());
        }

        @Test
        @DisplayName("should return 500 when provider is not VaultSecretProvider")
        void returns500WhenNotVaultProvider() {
            // Default mock is ISecretProvider (not VaultSecretProvider)
            Response resp = rest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("oldkey123", "newkey12345678"));
            assertEquals(500, resp.getStatus());
        }

        @Test
        @DisplayName("should return 200 on successful KEK rotation with VaultSecretProvider")
        @SuppressWarnings("unchecked")
        void returns200OnSuccess() throws Exception {
            var vaultProvider = mock(VaultSecretProvider.class);
            when(vaultProvider.isAvailable()).thenReturn(true);
            when(vaultProvider.rotateKek("oldkey123", "newkey12345678")).thenReturn(5);
            var vaultRest = new RestSecretStore(vaultProvider, secretResolver);

            Response resp = vaultRest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("oldkey123", "newkey12345678"));

            assertEquals(200, resp.getStatus());
            Map<String, Object> body = (Map<String, Object>) resp.getEntity();
            assertEquals(5, body.get("deksReEncrypted"));
            verify(secretResolver).invalidateAll();
        }

        @Test
        @DisplayName("should return 500 when VaultSecretProvider.rotateKek throws")
        void returns500OnRotateKekFailure() throws Exception {
            var vaultProvider = mock(VaultSecretProvider.class);
            when(vaultProvider.isAvailable()).thenReturn(true);
            when(vaultProvider.rotateKek(any(), any()))
                    .thenThrow(new ISecretProvider.SecretProviderException("Key derivation failed"));
            var vaultRest = new RestSecretStore(vaultProvider, secretResolver);

            Response resp = vaultRest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("oldkey123", "newkey12345678"));

            assertEquals(500, resp.getStatus());
        }

        @Test
        @DisplayName("should return 400 when newMasterKey is null")
        void returns400WhenNewKeyNull() {
            Response resp = rest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("oldkey123", null));
            assertEquals(400, resp.getStatus());
        }

        @Test
        @DisplayName("should return 400 when oldMasterKey is blank")
        void returns400WhenOldKeyBlank() {
            Response resp = rest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("   ", "newkey12345678"));
            assertEquals(400, resp.getStatus());
        }
    }
}
