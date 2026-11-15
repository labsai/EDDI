/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

    private static final Instant AT = Instant.parse("2026-11-15T10:00:00.123Z");

    private IErasedSubjectStore store;
    private GdprPseudonymizer pseudonymizer;
    private AuditLedgerService ledger;
    private ErasedSubjects subjects;

    private static String token(String userId) {
        return "gdpr-erased:s1:" + userId.hashCode();
    }

    @BeforeEach
    void setUp() {
        store = mock(IErasedSubjectStore.class);
        when(store.erasedAt(any())).thenReturn(Optional.empty());
        pseudonymizer = mock(GdprPseudonymizer.class);
        when(pseudonymizer.stableTokenFor(anyString())).thenAnswer(inv -> token(inv.getArgument(0)));
        ledger = mock(AuditLedgerService.class);
        when(ledger.pseudonymOf("bob")).thenReturn("gdpr-erased:k1:bob");
        subjects = new ErasedSubjects(store, pseudonymizer, ledger);
    }

    @Test
    @DisplayName("records the keyed token, never the raw id")
    void recordsToken() {
        subjects.record("bob", AT);

        verify(store).record(token("bob"), AT);
        verify(store, never()).record(eq("bob"), any());
    }

    @Test
    @DisplayName("looks up the stable token only, and caches a positive answer that covers the record")
    void lookupAndCachePositive() {
        when(store.erasedAt(any())).thenReturn(Optional.of(AT));

        assertTrue(subjects.covers("bob", AT.minusSeconds(10)));
        assertTrue(subjects.covers("bob", AT));

        verify(store, times(1)).erasedAt(List.of(token("bob")));
    }

    @Test
    @DisplayName("a \"not erased\" answer is never cached: another node's erasure is seen on the very next check")
    void negativeNotCached() {
        IErasedSubjectStore shared = new MapStore();
        var nodeA = new ErasedSubjects(shared, pseudonymizer, ledger);
        var nodeB = new ErasedSubjects(shared, pseudonymizer, ledger);

        assertFalse(nodeB.covers("bob", AT));
        nodeA.record("bob", AT);

        assertTrue(nodeB.covers("bob", AT), "a stale negative must not let a raw id be stored");
    }

    @Test
    @DisplayName("a cached erasure time that does not cover the record is re-read, so a later erasure on another node counts")
    void cachedPositiveNotCoveringIsReread() {
        IErasedSubjectStore shared = new MapStore();
        var nodeA = new ErasedSubjects(shared, pseudonymizer, ledger);
        var nodeB = new ErasedSubjects(shared, pseudonymizer, ledger);
        nodeA.record("bob", AT);
        assertTrue(nodeB.covers("bob", AT));

        nodeA.record("bob", AT.plusSeconds(3600));

        assertTrue(nodeB.covers("bob", AT.plusSeconds(1800)));
    }

    @Test
    @DisplayName("a decision in the same millisecond as the erasure is covered (the stores keep milliseconds)")
    void sameMillisecond() {
        when(store.erasedAt(any())).thenReturn(Optional.of(AT));

        assertTrue(subjects.covers("bob", AT.plusNanos(400_000)));
        assertFalse(subjects.covers("bob", AT.plusMillis(1)));
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
        assertFalse(subjects.covers(identity, AT));
        verify(store, never()).erasedAt(any());
    }

    @Test
    @DisplayName("a read failure propagates (unknown is not \"no\") and is not cached")
    void readFailure() {
        when(store.erasedAt(any())).thenThrow(new IllegalStateException("down")).thenReturn(Optional.of(AT));

        assertThrows(IllegalStateException.class, () -> subjects.covers("bob", AT));
        assertTrue(subjects.covers("bob", AT));
    }

    @Test
    @DisplayName("a token that cannot be derived (no salt) propagates instead of answering \"not erased\"")
    void tokenFailure() {
        when(pseudonymizer.stableTokenFor(anyString())).thenThrow(new IllegalStateException("no salt"));

        assertThrows(IllegalStateException.class, () -> subjects.covers("bob", AT));
        assertThrows(IllegalStateException.class, () -> subjects.record("bob", AT));
        verify(store, never()).erasedAt(any());
    }

    @Test
    @DisplayName("null arguments to record are ignored; the pseudonym is the ledger's")
    void edges() {
        subjects.record(null, AT);
        subjects.record("bob", null);
        verify(store, never()).record(anyString(), any());
        assertEquals("gdpr-erased:k1:bob", subjects.pseudonymOf("bob"));
    }

    /** A store shared by two "nodes", with the max-time upsert of the real ones. */
    private static final class MapStore implements IErasedSubjectStore {
        private final Map<String, Instant> rows = new HashMap<>();

        @Override
        public void record(String token, Instant erasedAt) {
            rows.merge(token, erasedAt, (a, b) -> a.isAfter(b) ? a : b);
        }

        @Override
        public Optional<Instant> erasedAt(Collection<String> tokens) {
            return tokens.stream().map(rows::get).filter(Objects::nonNull).max(Instant::compareTo);
        }
    }
}
