/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.secrets.ISecretProvider.GrantConflictException;
import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The append every new agent that uses a restricted secret needs.
 * <p>
 * The provider is a small synchronized fake with a real compare-and-set, the
 * contract {@code VaultSecretProvider} implements — so the concurrency test
 * proves the retry, not a mock's scripted answers.
 */
@DisplayName("VaultGrantService — append one agent to a grant")
class VaultGrantServiceTest {

    private static final SecretReference SECRET = new SecretReference("default", "gemini-api-key");
    private static final String EXISTING = "aaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String NEW_AGENT = "bbbbbbbbbbbbbbbbbbbbbbbb";

    /** One secret's grant, with the guarded write the real provider performs. */
    static final class FakeGrantStore {
        private List<String> grant;
        private int writes;
        private CountDownLatch readBarrier;

        FakeGrantStore(List<String> grant) {
            this.grant = new ArrayList<>(grant);
        }

        SecretMetadata read() throws SecretNotFoundException {
            SecretMetadata metadata;
            synchronized (this) {
                if (grant == null) {
                    throw new SecretNotFoundException("not found");
                }
                metadata = metadata(grant);
            }
            CountDownLatch barrier = readBarrier;
            if (barrier != null) {
                // Hold every reader until all have read: forces the lost-update interleaving.
                barrier.countDown();
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return metadata;
        }

        synchronized SecretMetadata write(List<String> next, List<String> expected) throws GrantConflictException {
            if (!SecretMetadata.sameGrant(grant, expected)) {
                throw new GrantConflictException("changed", grant);
            }
            grant = new ArrayList<>(next);
            writes++;
            return metadata(grant);
        }

        private static SecretMetadata metadata(List<String> grant) {
            return new SecretMetadata("default", "gemini-api-key", null, null, null, "0000000000000000", "desc", List.copyOf(grant));
        }
    }

    private FakeGrantStore store;
    private AuditLedgerService ledger;
    private VaultGrantService service;

    @BeforeEach
    void setUp() throws Exception {
        store = new FakeGrantStore(List.of(EXISTING));
        ISecretProvider provider = mock(ISecretProvider.class);
        when(provider.getMetadata(SECRET)).thenAnswer(invocation -> store.read());
        when(provider.updateGrant(any(), anyList(), any(), anyList()))
                .thenAnswer(invocation -> store.write(invocation.getArgument(1), invocation.getArgument(3)));
        ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        service = new VaultGrantService(provider, ledger);
    }

    @Test
    @DisplayName("appends the agent and keeps everything already on the grant")
    void appends() throws Exception {
        var result = service.grantAgent(SECRET, NEW_AGENT, "admin", "rest", false);

        assertTrue(result.changed());
        assertEquals(List.of(EXISTING, NEW_AGENT), result.after().allowedAgents());
        assertEquals(List.of(EXISTING), result.before().allowedAgents());
        assertEquals(List.of(EXISTING, NEW_AGENT), store.grant);
    }

    @Test
    @DisplayName("idempotent: an agent already granted is not written again, nor audited")
    void idempotent() throws Exception {
        service.grantAgent(SECRET, NEW_AGENT, "admin", "rest", false);
        var again = service.grantAgent(SECRET, NEW_AGENT, "admin", "rest", false);

        assertFalse(again.changed());
        assertEquals(1, store.writes);
        verify(ledger).submit(any());
    }

    @Test
    @DisplayName("a secret open to every agent is never touched — no '*' becomes a list")
    void wildcardUntouched() throws Exception {
        store = new FakeGrantStore(List.of(SecretMetadata.WILDCARD_AGENT));

        var result = service.grantAgent(SECRET, NEW_AGENT, "admin", "setup", false);

        assertFalse(result.changed());
        assertEquals(0, store.writes);
        assertEquals(List.of(SecretMetadata.WILDCARD_AGENT), store.grant);
        verify(ledger, never()).submit(any());
    }

    @Test
    @DisplayName("dryRun shows the new grant and writes nothing, audits nothing")
    void dryRunWritesNothing() throws Exception {
        var result = service.grantAgent(SECRET, NEW_AGENT, "admin", "rest", true);

        assertTrue(result.changed());
        assertEquals(List.of(EXISTING, NEW_AGENT), result.after().allowedAgents());
        assertEquals(0, store.writes);
        assertEquals(List.of(EXISTING), store.grant);
        verify(ledger, never()).submit(any());
    }

    @Test
    @DisplayName("every real change writes an audit entry naming actor, secret, agent and origin")
    void auditsTheChange() throws Exception {
        service.grantAgent(SECRET, NEW_AGENT, "alice", "setup", false);

        var captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger).submit(captor.capture());
        AuditEntry entry = captor.getValue();
        assertEquals(VaultGrantService.AUDIT_TASK_ID, entry.taskId());
        assertEquals(VaultGrantService.AUDIT_TASK_TYPE, entry.taskType());
        assertEquals(NEW_AGENT, entry.agentId());
        assertEquals("alice", entry.userId());
        assertEquals("${vault:gemini-api-key}", entry.input().get("secret"));
        assertEquals("setup", entry.input().get("origin"));
        assertEquals(List.of(EXISTING), entry.output().get("previousAllowedAgents"));
        assertEquals(List.of(EXISTING, NEW_AGENT), entry.output().get("allowedAgents"));
        // Names only: the stored checksum never reaches the ledger.
        assertFalse(entry.output().toString().contains("0000000000000000"));
    }

    @Test
    @DisplayName("an unknown secret is reported as not found")
    void unknownSecret() {
        store = new FakeGrantStore(List.of());
        store.grant = null;
        assertThrows(SecretNotFoundException.class, () -> service.grantAgent(SECRET, NEW_AGENT, "admin", "rest", false));
    }

    @Test
    @DisplayName("concurrent appends both land: the loser of the compare-and-set re-reads and retries")
    void concurrentAppendsBothLand() throws Exception {
        String third = "cccccccccccccccccccccccc";
        // Both callers read [EXISTING] before either writes — the lost-update case a
        // read-modify-write in the REST layer would get wrong.
        store.readBarrier = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<VaultGrantService.AppendResult> first = pool.submit(() -> service.grantAgent(SECRET, NEW_AGENT, "a", "rest", false));
            Future<VaultGrantService.AppendResult> second = pool.submit(() -> service.grantAgent(SECRET, third, "b", "rest", false));
            assertTrue(first.get(10, TimeUnit.SECONDS).changed());
            assertTrue(second.get(10, TimeUnit.SECONDS).changed());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Set.of(EXISTING, NEW_AGENT, third), new HashSet<>(store.grant));
        assertEquals(2, store.writes);
    }
}
