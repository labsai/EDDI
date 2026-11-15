/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ErasedSubjects")
class ErasedSubjectsTest {

    private static final Instant AT = Instant.parse("2026-11-15T10:00:00Z");

    private IErasedSubjectStore store;
    private ErasedSubjects subjects;

    @BeforeEach
    void setUp() {
        store = mock(IErasedSubjectStore.class);
        when(store.erasedAt(any())).thenReturn(Optional.empty());
        GdprPseudonymizer pseudonymizer = mock(GdprPseudonymizer.class);
        when(pseudonymizer.stableTokenFor(anyString())).thenAnswer(inv -> "gdpr-erased:s1:" + inv.getArgument(0).hashCode());
        AuditLedgerService ledger = mock(AuditLedgerService.class);
        when(ledger.pseudonymOf("bob")).thenReturn("gdpr-erased:k1:bob");
        subjects = new ErasedSubjects(store, pseudonymizer, ledger);
    }

    @Test
    @DisplayName("records the keyed token, never the raw id")
    void recordsToken() {
        subjects.record("bob", AT);

        verify(store).record("gdpr-erased:s1:" + "bob".hashCode(), AT);
        verify(store, never()).record(eq("bob"), any());
    }

    @Test
    @DisplayName("looks up the salted and the unsalted token in one read, and caches the answer")
    @SuppressWarnings("unchecked")
    void lookupAndCache() {
        when(store.erasedAt(any())).thenReturn(Optional.of(AT));

        assertEquals(Optional.of(AT), subjects.erasedAt("bob"));
        assertEquals(Optional.of(AT), subjects.erasedAt("bob"));

        var captor = ArgumentCaptor.forClass(Collection.class);
        verify(store, times(1)).erasedAt(captor.capture());
        assertEquals(Set.of("gdpr-erased:s1:" + "bob".hashCode(), AuditHmac.pseudonymFor("bob")), Set.copyOf(captor.getValue()));
    }

    @Test
    @DisplayName("recording on this node invalidates its cached \"not erased\" at once")
    void recordInvalidatesCache() {
        assertFalse(subjects.covers("bob", AT));
        when(store.erasedAt(any())).thenReturn(Optional.of(AT));

        subjects.record("bob", AT);

        assertTrue(subjects.covers("bob", AT));
    }

    @Test
    @DisplayName("covers what was decided at or before the erasure, or at an unknown time; not what came after")
    void coversWindow() {
        when(store.erasedAt(any())).thenReturn(Optional.of(AT));

        assertTrue(subjects.covers("bob", AT.minusSeconds(1)));
        assertTrue(subjects.covers("bob", AT));
        assertTrue(subjects.covers("bob", null));
        assertFalse(subjects.covers("bob", AT.plusSeconds(1)));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "unknown", "anonymous", "system:timeout", "gdpr-erased:k1:abc"})
    @DisplayName("identities that cannot be a person are answered without a read")
    void nonPersonal(String identity) {
        assertEquals(Optional.empty(), subjects.erasedAt(identity));
        verify(store, never()).erasedAt(any());
    }

    @Test
    @DisplayName("a read failure propagates (unknown is not \"no\") and is not cached")
    void readFailure() {
        when(store.erasedAt(any())).thenThrow(new IllegalStateException("down")).thenReturn(Optional.of(AT));

        assertThrows(IllegalStateException.class, () -> subjects.erasedAt("bob"));
        assertEquals(Optional.of(AT), subjects.erasedAt("bob"));
    }

    @Test
    @DisplayName("null arguments to record are ignored; the pseudonym is the ledger's")
    void edges() {
        subjects.record(null, AT);
        subjects.record("bob", null);
        verify(store, never()).record(anyString(), any());
        assertEquals("gdpr-erased:k1:bob", subjects.pseudonymOf("bob"));
    }
}
