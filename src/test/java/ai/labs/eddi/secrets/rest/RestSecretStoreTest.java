/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.ISecretProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultGrantImpactAnalyzer;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.ws.rs.core.Response;
import ai.labs.eddi.secrets.impl.VaultSecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
    private VaultGrantImpactAnalyzer grantImpactAnalyzer;
    private RestSecretStore rest;

    @BeforeEach
    void setUp() {
        secretProvider = mock(ISecretProvider.class);
        secretResolver = mock(SecretResolver.class);
        grantImpactAnalyzer = mock(VaultGrantImpactAnalyzer.class);
        when(secretProvider.isAvailable()).thenReturn(true);
        when(grantImpactAnalyzer.agentsLosingAccess(any(), any()))
                .thenReturn(new VaultGrantImpactAnalyzer.GrantImpact(List.of(), true));
        rest = new RestSecretStore(secretProvider, secretResolver, grantImpactAnalyzer);
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

    // ─── updateGrant ───

    /** Metadata for an existing secret granted to {@code allowedAgents}. */
    private static SecretMetadata existing(List<String> allowedAgents) {
        return new SecretMetadata("default", "llm-api-key", Instant.parse("2024-01-01T00:00:00Z"), null, null, "checksum-abc",
                "LLM provider key", allowedAgents);
    }

    /**
     * Makes the provider behave like a real one: the secret exists, and an update
     * echoes back the grant it was given.
     */
    @SuppressWarnings("unchecked")
    private void givenSecretGrantedTo(List<String> allowedAgents) throws Exception {
        SecretMetadata before = existing(allowedAgents);
        when(secretProvider.getMetadata(any(SecretReference.class))).thenReturn(before);
        when(secretProvider.updateGrant(any(SecretReference.class), any(), any())).thenAnswer(invocation -> {
            List<String> grant = invocation.getArgument(1);
            String description = invocation.getArgument(2);
            return new SecretMetadata(before.tenantId(), before.keyName(), before.createdAt(), before.lastAccessedAt(), before.lastRotatedAt(),
                    before.checksum(), description != null ? description : before.description(), grant);
        });
    }

    @Test
    void updateGrant_widensWithoutAValue() throws Exception {
        givenSecretGrantedTo(List.of("agentOne", "agentTwo"));

        Response resp = rest.updateGrant("default", "llm-api-key", false,
                new IRestSecretStore.GrantRequest(List.of("agentOne", "agentTwo", "agentThree"), null));

        assertEquals(200, resp.getStatus());
        verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("agentOne", "agentTwo", "agentThree")), isNull());
        // The whole point: nothing on this path can write a value.
        verify(secretProvider, never()).store(any(), any(), any(), any());

        Map<String, Object> body = entityOf(resp);
        assertEquals(List.of("agentOne", "agentTwo", "agentThree"), body.get("allowedAgents"));
        assertEquals(List.of("agentOne", "agentTwo"), body.get("previousAllowedAgents"));
        assertEquals(Boolean.FALSE, body.get("grantsAllAgents"));
        assertFalse(body.containsKey("warning"));
    }

    @Test
    void updateGrant_tightens() throws Exception {
        givenSecretGrantedTo(List.of("agentOne", "agentTwo"));

        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("agentOne"), null));

        assertEquals(200, resp.getStatus());
        verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("agentOne")), isNull());
        assertEquals(List.of("agentOne"), entityOf(resp).get("allowedAgents"));
    }

    @Test
    void updateGrant_setsTheWildcard() throws Exception {
        givenSecretGrantedTo(List.of("agentOne"));

        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("*"), null));

        assertEquals(200, resp.getStatus());
        verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("*")), isNull());
        assertEquals(Boolean.TRUE, entityOf(resp).get("grantsAllAgents"));
    }

    @Test
    void updateGrant_clearsTheWildcard() throws Exception {
        givenSecretGrantedTo(List.of("*"));

        Response resp = rest.updateGrant("default", "llm-api-key", false,
                new IRestSecretStore.GrantRequest(List.of("agentOne", "agentTwo"), null));

        assertEquals(200, resp.getStatus());
        verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("agentOne", "agentTwo")), isNull());
        Map<String, Object> body = entityOf(resp);
        assertEquals(Boolean.FALSE, body.get("grantsAllAgents"));
        assertEquals(List.of("*"), body.get("previousAllowedAgents"));
    }

    @Test
    void updateGrant_collapsesAWildcardMixedWithAgentIdsOntoTheWildcardAlone() throws Exception {
        // ["*", "agentOne"] already means "everyone" to VaultGrantChecker. Storing it
        // verbatim would read to the next operator as a narrow grant.
        givenSecretGrantedTo(List.of("agentOne"));

        rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("*", "agentOne"), null));

        verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("*")), isNull());
    }

    @Test
    void updateGrant_dropsDuplicatesButKeepsOrder() throws Exception {
        givenSecretGrantedTo(List.of("agentOne"));

        rest.updateGrant("default", "llm-api-key", false,
                new IRestSecretStore.GrantRequest(List.of("agentTwo", "agentOne", "agentTwo"), null));

        verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("agentTwo", "agentOne")), isNull());
    }

    @Test
    void updateGrant_replacesTheDescriptionWhenOneIsGiven() throws Exception {
        givenSecretGrantedTo(List.of("agentOne"));

        Response resp = rest.updateGrant("default", "llm-api-key", false,
                new IRestSecretStore.GrantRequest(List.of("agentOne"), "LLM provider key (production)"));

        verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("agentOne")), eq("LLM provider key (production)"));
        assertEquals("LLM provider key (production)", entityOf(resp).get("description"));
    }

    @Test
    void updateGrant_returns404WhenTheSecretDoesNotExist() throws Exception {
        when(secretProvider.getMetadata(any(SecretReference.class))).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        Response resp = rest.updateGrant("default", "no-such-key", false, new IRestSecretStore.GrantRequest(List.of("*"), null));

        assertEquals(404, resp.getStatus());
        // A grant edit must never conjure a secret: a typo has to fail, not create a
        // valueless entry that later fails to decrypt.
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns404WhenTheSecretIsDeletedMidFlight() throws Exception {
        when(secretProvider.getMetadata(any(SecretReference.class))).thenReturn(existing(List.of("agentOne")));
        when(secretProvider.updateGrant(any(SecretReference.class), any(), any()))
                .thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("*"), null));

        assertEquals(404, resp.getStatus());
    }

    @Test
    void updateGrant_returns400WhenAllowedAgentsIsOmitted() throws Exception {
        givenSecretGrantedTo(List.of("agentOne"));

        // storeSecret defaults a missing list to ["*"]. On an EDIT that would silently
        // open a narrowed secret to every agent because a field was left out.
        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(null, "still here"));

        assertEquals(400, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns400OnAnEmptyList() throws Exception {
        // [] means "everyone" everywhere else. On an edit, a client that filtered its
        // list down to nothing must not open the secret with a 200.
        givenSecretGrantedTo(List.of("agentOne"));

        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of(), null));

        assertEquals(400, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns400WhenBodyIsNull() throws Exception {
        Response resp = rest.updateGrant("default", "llm-api-key", false, null);
        assertEquals(400, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns400OnABlankEntry() throws Exception {
        Response resp = rest.updateGrant("default", "llm-api-key", false,
                new IRestSecretStore.GrantRequest(Arrays.asList("agentOne", "  "), null));
        assertEquals(400, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns400OnAnEntryThatIsNotAnAgentId() throws Exception {
        Response resp = rest.updateGrant("default", "llm-api-key", false,
                new IRestSecretStore.GrantRequest(List.of("../etc/passwd"), null));
        assertEquals(400, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns400OnAnAbsurdlyLongList() throws Exception {
        List<String> tooMany = IntStream.range(0, 501).mapToObj(i -> "agent" + i).toList();

        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(tooMany, null));

        assertEquals(400, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns400OnAnInvalidKeyName() throws Exception {
        Response resp = rest.updateGrant("default", "../etc/passwd", false, new IRestSecretStore.GrantRequest(List.of("*"), null));
        assertEquals(400, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns503WhenVaultUnavailable() throws Exception {
        when(secretProvider.isAvailable()).thenReturn(false);
        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("*"), null));
        assertEquals(503, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_returns500OnAProviderFailure() throws Exception {
        when(secretProvider.getMetadata(any(SecretReference.class))).thenReturn(existing(List.of("agentOne")));
        when(secretProvider.updateGrant(any(SecretReference.class), any(), any()))
                .thenThrow(new ISecretProvider.SecretProviderException("boom"));

        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("*"), null));

        assertEquals(500, resp.getStatus());
    }

    @Test
    void updateGrant_doesNotInvalidateTheSecretCache() throws Exception {
        // storeSecret has to invalidate because the plaintext may have changed. Here
        // it cannot have, and the cache plays no part in the grant decision — so
        // invalidating would only force a needless decrypt for every agent using the
        // key.
        givenSecretGrantedTo(List.of("agentOne"));

        rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("agentOne", "agentTwo"), null));

        verify(secretResolver, never()).invalidateCache(any());
        verify(secretResolver, never()).invalidateAll();
    }

    @Test
    void updateGrant_warnsAboutDeployedAgentsThatWouldLoseAccess() throws Exception {
        givenSecretGrantedTo(List.of("agentOne", "agentTwo"));
        when(grantImpactAnalyzer.agentsLosingAccess(any(), eq(List.of("agentOne")))).thenReturn(new VaultGrantImpactAnalyzer.GrantImpact(
                List.of(new VaultGrantImpactAnalyzer.AffectedAgent("agentTwo", 3, "production")), true));

        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("agentOne"), null));

        assertEquals(200, resp.getStatus());
        Map<String, Object> body = entityOf(resp);
        assertEquals(List.of(new VaultGrantImpactAnalyzer.AffectedAgent("agentTwo", 3, "production")), body.get("agentsLosingAccess"));
        assertTrue(String.valueOf(body.get("warning")).contains("next deployment"),
                () -> "the warning should say what actually breaks, got: " + body.get("warning"));
    }

    @Test
    void updateGrant_reportsAnIncompleteImpactScanRatherThanAnEmptyOne() throws Exception {
        // "Could not tell" must not arrive looking like "nothing breaks".
        givenSecretGrantedTo(List.of("agentOne", "agentTwo"));
        when(grantImpactAnalyzer.agentsLosingAccess(any(), any()))
                .thenReturn(new VaultGrantImpactAnalyzer.GrantImpact(List.of(), false));

        Map<String, Object> body = entityOf(
                rest.updateGrant("default", "llm-api-key", true, new IRestSecretStore.GrantRequest(List.of("agentOne"), null)));

        assertEquals(Boolean.FALSE, body.get("agentsLosingAccessComplete"));
        assertTrue(String.valueOf(body.get("warning")).contains("may be short"), () -> "got: " + body.get("warning"));
    }

    @Test
    void updateGrant_marksACompleteImpactScanAsComplete() throws Exception {
        givenSecretGrantedTo(List.of("agentOne", "agentTwo"));

        Map<String, Object> body = entityOf(
                rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("agentOne"), null)));

        assertEquals(Boolean.TRUE, body.get("agentsLosingAccessComplete"));
        assertFalse(body.containsKey("warning"));
    }

    @Test
    void updateGrant_dryRunWritesNothingButStillReportsTheImpact() throws Exception {
        givenSecretGrantedTo(List.of("agentOne", "agentTwo"));
        when(grantImpactAnalyzer.agentsLosingAccess(any(), eq(List.of("agentOne")))).thenReturn(new VaultGrantImpactAnalyzer.GrantImpact(
                List.of(new VaultGrantImpactAnalyzer.AffectedAgent("agentTwo", 3, "production")), true));

        Response resp = rest.updateGrant("default", "llm-api-key", true,
                new IRestSecretStore.GrantRequest(List.of("agentOne"), "would-be description"));

        assertEquals(200, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
        Map<String, Object> body = entityOf(resp);
        assertEquals(Boolean.TRUE, body.get("dryRun"));
        // The projection, not the current state — otherwise the preview shows the
        // operator what they already have.
        assertEquals(List.of("agentOne"), body.get("allowedAgents"));
        assertEquals(List.of("agentOne", "agentTwo"), body.get("previousAllowedAgents"));
        assertEquals("would-be description", body.get("description"));
        assertEquals(1, ((List<?>) body.get("agentsLosingAccess")).size());
    }

    @Test
    void updateGrant_dryRunStill404sForAnUnknownKey() throws Exception {
        when(secretProvider.getMetadata(any(SecretReference.class))).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));

        Response resp = rest.updateGrant("default", "no-such-key", true, new IRestSecretStore.GrantRequest(List.of("*"), null));

        assertEquals(404, resp.getStatus());
    }

    @Test
    void updateGrant_reportsTimestampsUnchanged() throws Exception {
        // Echoed into the response so the operator can see for themselves that a
        // grant edit was not a rotation.
        givenSecretGrantedTo(List.of("agentOne"));

        Map<String, Object> body = entityOf(
                rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("agentOne", "agentTwo"), null)));

        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), body.get("createdAt"));
        assertNull(body.get("lastRotatedAt"));
    }

    // ─── S6: expectedAllowedAgents precondition ───

    @Test
    void updateGrant_passesThePreconditionToTheProvider() throws Exception {
        givenSecretGrantedTo(List.of("agentOne", "agentTwo"));
        when(secretProvider.updateGrant(any(SecretReference.class), any(), any(), any()))
                .thenReturn(existing(List.of("agentOne")));

        Response resp = rest.updateGrant("default", "llm-api-key", false,
                new IRestSecretStore.GrantRequest(List.of("agentOne"), null, List.of("agentOne", "agentTwo")));

        assertEquals(200, resp.getStatus());
        verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("agentOne")), isNull(), eq(List.of("agentOne", "agentTwo")));
    }

    @Test
    void updateGrant_returns409WithTheCurrentGrantOnAConflict() throws Exception {
        givenSecretGrantedTo(List.of("agentOne", "agentTwo"));
        when(secretProvider.updateGrant(any(SecretReference.class), any(), any(), any()))
                .thenThrow(new ISecretProvider.GrantConflictException("changed", List.of("agentTwo")));

        Response resp = rest.updateGrant("default", "llm-api-key", false,
                new IRestSecretStore.GrantRequest(List.of("agentOne", "agentThree"), null, List.of("agentOne", "agentTwo")));

        assertEquals(409, resp.getStatus());
        assertEquals(List.of("agentTwo"), entityOf(resp).get("allowedAgents"));
    }

    @Test
    void updateGrant_dryRunAnswersAStalePreconditionWith409() throws Exception {
        givenSecretGrantedTo(List.of("agentOne"));

        Response resp = rest.updateGrant("default", "llm-api-key", true,
                new IRestSecretStore.GrantRequest(List.of("agentOne", "agentThree"), null, List.of("agentOne", "agentTwo")));

        assertEquals(409, resp.getStatus());
        verify(secretProvider, never()).updateGrant(any(), any(), any(), any());
        verify(secretProvider, never()).updateGrant(any(), any(), any());
    }

    @Test
    void updateGrant_rejectsANullEntryInThePrecondition() throws Exception {
        givenSecretGrantedTo(List.of("agentOne"));
        var expected = new ArrayList<String>();
        expected.add(null);

        Response resp = rest.updateGrant("default", "llm-api-key", false, new IRestSecretStore.GrantRequest(List.of("agentOne"), null, expected));

        assertEquals(400, resp.getStatus());
    }

    @Test
    void grantRequest_deserializesWithAndWithoutThePrecondition() throws Exception {
        var mapper = new ObjectMapper();

        var withPrecondition = mapper.readValue("{\"allowedAgents\":[\"a\"],\"expectedAllowedAgents\":[\"a\",\"b\"]}",
                IRestSecretStore.GrantRequest.class);
        var without = mapper.readValue("{\"allowedAgents\":[\"a\"],\"description\":\"d\"}", IRestSecretStore.GrantRequest.class);

        assertEquals(List.of("a", "b"), withPrecondition.expectedAllowedAgents());
        assertNull(without.expectedAllowedAgents());
        assertEquals("d", without.description());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entityOf(Response response) {
        return (Map<String, Object>) response.getEntity();
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
                    new IRestSecretStore.KekRotationRequest("oldkey123", "test-new-master-key"));
            assertEquals(503, resp.getStatus());
        }

        @Test
        @DisplayName("should return 500 when provider is not VaultSecretProvider")
        void returns500WhenNotVaultProvider() {
            // Default mock is ISecretProvider (not VaultSecretProvider)
            Response resp = rest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("oldkey123", "test-new-master-key"));
            assertEquals(500, resp.getStatus());
        }

        @Test
        @DisplayName("should return 200 on successful KEK rotation with VaultSecretProvider")
        @SuppressWarnings("unchecked")
        void returns200OnSuccess() throws Exception {
            var vaultProvider = mock(VaultSecretProvider.class);
            when(vaultProvider.isAvailable()).thenReturn(true);
            when(vaultProvider.rotateKek("oldkey123", "test-new-master-key")).thenReturn(5);
            var vaultRest = new RestSecretStore(vaultProvider, secretResolver, grantImpactAnalyzer);

            Response resp = vaultRest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("oldkey123", "test-new-master-key"));

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
            var vaultRest = new RestSecretStore(vaultProvider, secretResolver, grantImpactAnalyzer);

            Response resp = vaultRest.rotateKek(
                    new IRestSecretStore.KekRotationRequest("oldkey123", "test-new-master-key"));

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
                    new IRestSecretStore.KekRotationRequest("   ", "test-new-master-key"));
            assertEquals(400, resp.getStatus());
        }
    }

    // ─── resetTenant ───

    @Nested
    @DisplayName("resetTenant")
    class ResetTenantTests {

        @Test
        @DisplayName("should return 200 with secretsDeleted count on success")
        @SuppressWarnings("unchecked")
        void returns200OnSuccess() throws Exception {
            when(secretProvider.resetTenant("default")).thenReturn(3);

            Response resp = rest.resetTenant("default");

            assertEquals(200, resp.getStatus());
            Map<String, Object> body = (Map<String, Object>) resp.getEntity();
            assertEquals(3, body.get("secretsDeleted"));
            assertNotNull(body.get("message"));
            assertEquals("default", body.get("tenantId"));
        }

        @Test
        @DisplayName("should return 503 when vault is unavailable")
        void returns503WhenUnavailable() {
            when(secretProvider.isAvailable()).thenReturn(false);

            Response resp = rest.resetTenant("default");

            assertEquals(503, resp.getStatus());
        }

        @Test
        @DisplayName("should return 400 for path-traversal tenantId")
        void returns400ForPathTraversalTenantId() {
            Response resp = rest.resetTenant("../etc/passwd");

            assertEquals(400, resp.getStatus());
        }

        @Test
        @DisplayName("should return 400 for blank tenantId")
        void returns400ForBlankTenantId() {
            Response resp = rest.resetTenant(" ");

            assertEquals(400, resp.getStatus());
        }

        @Test
        @DisplayName("should return 500 when provider throws SecretProviderException")
        void returns500OnProviderException() throws Exception {
            when(secretProvider.resetTenant("default"))
                    .thenThrow(new ISecretProvider.SecretProviderException("DEK corrupted"));

            Response resp = rest.resetTenant("default");

            assertEquals(500, resp.getStatus());
        }

        @Test
        @DisplayName("should invalidate all cached secrets on success")
        void invalidatesCacheOnSuccess() throws Exception {
            when(secretProvider.resetTenant("default")).thenReturn(2);

            rest.resetTenant("default");

            verify(secretResolver).invalidateAll();
        }
    }
}
