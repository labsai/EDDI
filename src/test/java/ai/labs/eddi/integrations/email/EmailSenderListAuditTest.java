/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One audit entry per sender-list change: actor, diff, reason — never an
 * address in clear.
 */
@DisplayName("EmailSenderListAudit")
class EmailSenderListAuditTest {

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T value) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(value != null);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    @Test
    @DisplayName("records the actor, versions and diff, with exact addresses pseudonymised")
    void records() {
        var ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        var pseudonymizer = mock(GdprPseudonymizer.class);
        when(pseudonymizer.pseudonymFor("alice@partner.com")).thenReturn("p-alice");
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn((Principal) () -> "admin-ann");

        new EmailSenderListAudit(instance(ledger), instance(pseudonymizer), instance(identity)).changed("int-1", 3, 4,
                List.of("allow:alice@partner.com", "block:*@*.spam.example"), List.of(), "new supplier", false);

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger).submit(entry.capture());
        assertEquals("admin-ann", entry.getValue().userId());
        assertEquals(EmailSenderListAudit.TASK_ID, entry.getValue().taskId());
        assertEquals(List.of("allow:p-alice", "block:*@*.spam.example"), entry.getValue().output().get("added"));
        assertEquals("new supplier", entry.getValue().output().get("reason"));
        assertEquals(4, entry.getValue().output().get("version"));
        assertFalse(entry.getValue().output().toString().contains("alice@partner.com"), "no address in clear");
    }

    @Test
    @DisplayName("an address typed into the reason is pseudonymised, never written in clear")
    void reasonIsScrubbed() {
        var ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        var pseudonymizer = mock(GdprPseudonymizer.class);
        when(pseudonymizer.pseudonymFor("alice@partner.com")).thenReturn("p-alice");

        new EmailSenderListAudit(instance(ledger), instance(pseudonymizer), instance(null)).changed("int-1", 3, 4, List.of(), List.of(),
                "Allow Alice@partner.com (new supplier), not *@spam.example", false);

        var entry = ArgumentCaptor.forClass(AuditEntry.class);
        verify(ledger).submit(entry.capture());
        assertEquals("Allow p-alice (new supplier), not *@spam.example", entry.getValue().output().get("reason"));
    }

    @Test
    @DisplayName("a failing ledger never fails the change")
    void neverThrows() {
        var ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        doThrow(new IllegalStateException("down")).when(ledger).submit(any());

        new EmailSenderListAudit(instance(ledger), instance(null), instance(null)).changed("int-1", 3, 4, List.of("allow:a@b.com"), List.of(),
                null, false);
    }
}
