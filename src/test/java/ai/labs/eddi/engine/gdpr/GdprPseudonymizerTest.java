/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * L9 of the 2026-10-17 security review: the database-log pseudonym was an
 * unsalted SHA-256, which a list of candidate e-mail addresses reverses.
 */
class GdprPseudonymizerTest {

    private static final String USER = "alice@example.com";

    /** A metadata store backed by a map, with the insert-if-absent semantics. */
    private static ISecretPersistence metaStore(Map<String, String> values) {
        var persistence = mock(ISecretPersistence.class);
        when(persistence.setMetaValueIfAbsent(anyString(), anyString()))
                .thenAnswer(inv -> values.computeIfAbsent(inv.getArgument(0), k -> inv.getArgument(1)));
        return persistence;
    }

    @SuppressWarnings("unchecked")
    private static Instance<ISecretPersistence> instance(ISecretPersistence persistence) {
        Instance<ISecretPersistence> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(persistence);
        return instance;
    }

    private static AuditLedgerService unsignedLedger() {
        var ledger = mock(AuditLedgerService.class);
        // What pseudonymOf returns when the ledger holds no signing key.
        when(ledger.pseudonymOf(anyString())).thenAnswer(inv -> AuditHmac.pseudonymFor(inv.getArgument(0)));
        return ledger;
    }

    @Test
    void signingLedger_theLedgersKeyedPseudonymIsUsed() {
        var ledger = mock(AuditLedgerService.class);
        String keyed = AuditHmac.keyedPseudonymFor(USER, new byte[32]);
        when(ledger.pseudonymOf(USER)).thenReturn(keyed);
        var persistence = metaStore(new HashMap<>());

        assertEquals(keyed, new GdprPseudonymizer(ledger, instance(persistence)).pseudonymFor(USER));
        // No salt is minted while the ledger key serves.
        verifyNoInteractions(persistence);
    }

    @Test
    void unsignedLedger_aPersistedPerDeploymentSaltKeysIt() {
        var values = new HashMap<String, String>();
        var pseudonymizer = new GdprPseudonymizer(unsignedLedger(), instance(metaStore(values)));

        String pseudonym = pseudonymizer.pseudonymFor(USER);

        assertTrue(pseudonym.startsWith(AuditHmac.SALTED_PSEUDONYM_PREFIX), pseudonym);
        assertTrue(pseudonym.startsWith(AuditHmac.GDPR_PSEUDONYM_PREFIX), "must still read as already pseudonymised");
        // The point of the fix: not recomputable from the id alone.
        assertNotEquals(AuditHmac.pseudonymFor(USER), pseudonym);
        assertTrue(values.containsKey(GdprPseudonymizer.SALT_META_KEY));
        assertEquals(pseudonym, pseudonymizer.pseudonymFor(USER), "deterministic for one user");
        assertNotEquals(pseudonym, pseudonymizer.pseudonymFor("bob@example.com"));
    }

    @Test
    void everyReplica_adoptsTheFirstSaltWritten() {
        var shared = new HashMap<String, String>();
        String first = new GdprPseudonymizer(unsignedLedger(), instance(metaStore(shared))).pseudonymFor(USER);
        String second = new GdprPseudonymizer(unsignedLedger(), instance(metaStore(shared))).pseudonymFor(USER);

        assertEquals(first, second, "two replicas must erase one user to one pseudonym");
    }

    @Test
    void anotherDeployment_givesAnotherPseudonym() {
        String here = new GdprPseudonymizer(unsignedLedger(), instance(metaStore(new HashMap<>()))).pseudonymFor(USER);
        String there = new GdprPseudonymizer(unsignedLedger(), instance(metaStore(new HashMap<>()))).pseudonymFor(USER);

        assertNotEquals(here, there);
    }

    @Test
    void metadataStoreFailure_fallsBackToTheUnsaltedPseudonym_andRetriesNextTime() {
        var persistence = mock(ISecretPersistence.class);
        when(persistence.setMetaValueIfAbsent(anyString(), anyString()))
                .thenThrow(new PersistenceException("down"))
                .thenReturn("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
        var pseudonymizer = new GdprPseudonymizer(unsignedLedger(), instance(persistence));

        assertEquals(AuditHmac.pseudonymFor(USER), pseudonymizer.pseudonymFor(USER));
        assertTrue(pseudonymizer.pseudonymFor(USER).startsWith(AuditHmac.SALTED_PSEUDONYM_PREFIX));
    }

    @Test
    void noMetadataStore_fallsBackToTheUnsaltedPseudonym() {
        assertEquals(AuditHmac.pseudonymFor(USER), new GdprPseudonymizer(unsignedLedger(), null).pseudonymFor(USER));
    }

    @Test
    void nullUser_isRefused() {
        var pseudonymizer = new GdprPseudonymizer(unsignedLedger(), null);
        assertThrows(IllegalArgumentException.class, () -> pseudonymizer.pseudonymFor(null));
    }
}
