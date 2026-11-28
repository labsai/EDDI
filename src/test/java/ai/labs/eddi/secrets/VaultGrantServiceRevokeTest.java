/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.secrets.ISecretProvider.GrantConflictException;
import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Removing a permanently deleted agent from every grant that names it.
 * <p>
 * The provider is a fake over a map of grants with the provider's real
 * compare-and-set, so a conflict test proves the retry rather than a scripted
 * answer.
 */
@DisplayName("VaultGrantService — remove a deleted agent from every grant")
class VaultGrantServiceRevokeTest {

    private static final String DELETED = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String OTHER = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String SPACE_TENANT = "space-cccccccccccc";

    /**
     * Grants keyed by "tenant/key", written only through a guarded compare-and-set.
     */
    private final Map<String, List<String>> grants = new LinkedHashMap<>();
    private ISecretProvider provider;
    private AuditLedgerService ledger;
    private VaultGrantService service;

    @BeforeEach
    void setUp() throws Exception {
        grants.put("default/llm-key", List.of(DELETED, OTHER));
        grants.put("default/sole-key", List.of(DELETED));
        grants.put("default/open-key", List.of(SecretMetadata.WILDCARD_AGENT));
        grants.put("default/unrelated-key", List.of(OTHER));
        grants.put(SPACE_TENANT + "/space-key", List.of(OTHER, DELETED));

        provider = mock(ISecretProvider.class);
        when(provider.isAvailable()).thenReturn(true);
        when(provider.listTenants()).thenReturn(List.of(SecretReference.DEFAULT_TENANT, SPACE_TENANT));
        when(provider.listKeys(anyString())).thenAnswer(invocation -> listing(invocation.getArgument(0)));
        when(provider.getMetadata(any())).thenAnswer(invocation -> read(invocation.getArgument(0)));
        when(provider.updateGrant(any(), anyList(), any(), anyList()))
                .thenAnswer(invocation -> write(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(3)));
        ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        service = new VaultGrantService(provider, ledger);
    }

    private List<SecretMetadata> listing(String tenant) {
        List<SecretMetadata> out = new ArrayList<>();
        grants.forEach((key, grant) -> {
            if (key.startsWith(tenant + "/")) {
                out.add(metadata(tenant, key.substring(tenant.length() + 1), grant));
            }
        });
        return out;
    }

    private SecretMetadata read(SecretReference ref) throws SecretNotFoundException {
        List<String> grant = grants.get(ref.tenantId() + "/" + ref.keyName());
        if (grant == null) {
            throw new SecretNotFoundException("not found");
        }
        return metadata(ref.tenantId(), ref.keyName(), grant);
    }

    private synchronized SecretMetadata write(SecretReference ref, List<String> next, List<String> expected) throws GrantConflictException {
        String key = ref.tenantId() + "/" + ref.keyName();
        if (!SecretMetadata.sameGrant(grants.get(key), expected)) {
            throw new GrantConflictException("changed", grants.get(key));
        }
        grants.put(key, List.copyOf(next));
        return metadata(ref.tenantId(), ref.keyName(), next);
    }

    private static SecretMetadata metadata(String tenant, String key, List<String> grant) {
        return new SecretMetadata(tenant, key, null, null, null, "0000000000000000", "desc", List.copyOf(grant));
    }

    @Test
    @DisplayName("removes the id from every grant that lists it, in every tenant, and keeps everyone else")
    void removesEverywhere() {
        List<String> revoked = service.revokeDeletedAgent(DELETED, "admin");

        assertEquals(List.of("${vault:llm-key}", "${vault:" + SPACE_TENANT + "/space-key}"), revoked);
        assertEquals(List.of(OTHER), grants.get("default/llm-key"));
        assertEquals(List.of(OTHER), grants.get(SPACE_TENANT + "/space-key"));
    }

    @Test
    @DisplayName("never widens: a grant whose only id is the deleted agent keeps it, because an empty grant means every agent")
    void soleGranteeIsKept() {
        service.revokeDeletedAgent(DELETED, "admin");

        assertEquals(List.of(DELETED), grants.get("default/sole-key"));
    }

    @Test
    @DisplayName("an open grant and a grant that does not name the agent are not written at all")
    void untouchedGrants() throws Exception {
        service.revokeDeletedAgent(DELETED, "admin");

        assertEquals(List.of(SecretMetadata.WILDCARD_AGENT), grants.get("default/open-key"));
        assertEquals(List.of(OTHER), grants.get("default/unrelated-key"));
        // Two changed grants, two writes — nothing for the open, unrelated or sole
        // ones.
        verify(provider, times(2)).updateGrant(any(), anyList(), any(), anyList());
    }

    @Test
    @DisplayName("each removal is audited under its own task id, naming the agent removed")
    void audited() {
        service.revokeDeletedAgent(DELETED, "admin");

        ArgumentCaptor<AuditEntry> entries = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger, times(2)).submit(entries.capture());
        for (AuditEntry entry : entries.getAllValues()) {
            assertEquals(VaultGrantService.REVOKE_AUDIT_TASK_ID, entry.taskId());
            assertEquals(DELETED, entry.output().get("revokedAgentId"));
        }
    }

    @Test
    @DisplayName("a concurrent grant edit is kept: the conflict is re-read and retried")
    void conflictRetried() throws Exception {
        AtomicBoolean raced = new AtomicBoolean();
        doAnswer(invocation -> {
            SecretReference ref = invocation.getArgument(0);
            if ("llm-key".equals(ref.keyName()) && raced.compareAndSet(false, true)) {
                // Another admin adds an agent between our read and our write.
                grants.put("default/llm-key", List.of(DELETED, OTHER, "dddddddddddddddddddddddd"));
            }
            return write(ref, invocation.getArgument(1), invocation.getArgument(3));
        }).when(provider).updateGrant(any(), anyList(), any(), anyList());

        service.revokeDeletedAgent(DELETED, "admin");

        assertEquals(List.of(OTHER, "dddddddddddddddddddddddd"), grants.get("default/llm-key"));
    }

    @Test
    @DisplayName("one secret that cannot be written does not stop the sweep")
    void failureIsolated() throws Exception {
        doAnswer(invocation -> {
            SecretReference ref = invocation.getArgument(0);
            if ("llm-key".equals(ref.keyName())) {
                throw new SecretProviderException("disk full");
            }
            return write(ref, invocation.getArgument(1), invocation.getArgument(3));
        }).when(provider).updateGrant(any(), anyList(), any(), anyList());

        List<String> revoked = service.revokeDeletedAgent(DELETED, "admin");

        assertEquals(List.of("${vault:" + SPACE_TENANT + "/space-key}"), revoked);
        assertEquals(List.of(OTHER), grants.get(SPACE_TENANT + "/space-key"));
    }

    @Test
    @DisplayName("an unavailable vault is not swept")
    void unavailableVault() throws Exception {
        when(provider.isAvailable()).thenReturn(false);

        assertTrue(service.revokeDeletedAgent(DELETED, "admin").isEmpty());
        verify(provider, never()).listTenants();
    }
}
