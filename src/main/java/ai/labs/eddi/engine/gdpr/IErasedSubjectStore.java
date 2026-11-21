/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * Durable, cluster-wide record of the people GDPR erasure has erased: one row
 * per person, keyed by a keyed one-way token
 * ({@link GdprPseudonymizer#stableTokenFor}), holding only when they were last
 * erased. Never the raw id, never content. MongoDB
 * ({@code gdpr_erased_subjects} collection) and PostgreSQL
 * ({@code gdpr_erased_subjects} table) implementations, selected by
 * {@code DataStoreProducers}.
 * <p>
 * Read by projections that are rebuilt from the audit ledger (approval
 * analytics), so a rebuild cannot bring back an identity erasure removed. See
 * {@link ErasedSubjects}.
 *
 * @since 6.7.0
 */
public interface IErasedSubjectStore {

    /**
     * Records an erasure. Idempotent: recording the same token again keeps the
     * later of the two times, so a repeated erasure only ever widens the window.
     */
    void record(String token, Instant erasedAt);

    /**
     * The latest erasure time among the given tokens, or empty when none of them
     * was erased.
     */
    Optional<Instant> erasedAt(Collection<String> tokens);
}
