/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What every {@link IErasedSubjectStore} must do, run against MongoDB and
 * PostgreSQL by the store ITs. Starts from an empty store.
 */
public interface ErasedSubjectStoreContract {

    Instant AT = Instant.parse("2026-11-15T10:00:00Z");

    IErasedSubjectStore erasedSubjects();

    @Test
    @DisplayName("an unknown token, or no token, is not erased")
    default void unknown() {
        assertEquals(Optional.empty(), erasedSubjects().erasedAt(List.of("gdpr-erased:s1:nobody")));
        assertEquals(Optional.empty(), erasedSubjects().erasedAt(List.of()));
    }

    @Test
    @DisplayName("a recorded token is found, also among others")
    default void recorded() {
        erasedSubjects().record("gdpr-erased:s1:bob", AT);

        assertEquals(Optional.of(AT), erasedSubjects().erasedAt(List.of("gdpr-erased:s1:bob")));
        assertEquals(Optional.of(AT), erasedSubjects().erasedAt(List.of("gdpr-erased:other", "gdpr-erased:s1:bob")));
    }

    @Test
    @DisplayName("recording again is idempotent and keeps the later time")
    default void idempotentMax() {
        erasedSubjects().record("gdpr-erased:s1:bob", AT);
        erasedSubjects().record("gdpr-erased:s1:bob", AT);
        erasedSubjects().record("gdpr-erased:s1:bob", AT.minusSeconds(3600));
        assertEquals(Optional.of(AT), erasedSubjects().erasedAt(List.of("gdpr-erased:s1:bob")));

        erasedSubjects().record("gdpr-erased:s1:bob", AT.plusSeconds(60));
        assertEquals(Optional.of(AT.plusSeconds(60)), erasedSubjects().erasedAt(List.of("gdpr-erased:s1:bob")));
    }

    @Test
    @DisplayName("several matching tokens answer with the latest time")
    default void latestOfSeveral() {
        erasedSubjects().record("gdpr-erased:s1:bob", AT);
        erasedSubjects().record("gdpr-erased:unsalted-bob", AT.plusSeconds(5));

        assertEquals(Optional.of(AT.plusSeconds(5)), erasedSubjects().erasedAt(List.of("gdpr-erased:s1:bob", "gdpr-erased:unsalted-bob")));
    }
}
