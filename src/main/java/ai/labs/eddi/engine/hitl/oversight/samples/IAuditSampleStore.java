/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.samples;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The sampled-audit review queue: MongoDB ({@code audit_samples} collection)
 * and PostgreSQL ({@code audit_samples} table) implementations, selected by
 * {@code DataStoreProducers}.
 *
 * @since 6.7.0
 */
public interface IAuditSampleStore {

    /** Stores a new sample. */
    void insert(AuditSample sample);

    Optional<AuditSample> read(String id);

    /**
     * Matching samples: PENDING ones by due date (oldest first), anything else
     * newest first.
     */
    List<AuditSample> query(SampleFilter filter, int skip, int limit);

    long count(SampleFilter filter);

    /**
     * Records a review, compare-and-set: only a PENDING sample still at
     * {@code expectedVersion} changes.
     *
     * @return the reviewed sample, or empty when it was reviewed, expired or
     *         changed meanwhile
     */
    Optional<AuditSample> review(AuditSample reviewed, long expectedVersion);

    /** Sets PENDING samples past their due date to EXPIRED. */
    long expireOverdue(Instant now);

    /** Deletes samples taken before the cutoff (retention). */
    long deleteSampledBefore(Instant cutoff);

    // === GDPR ===

    List<AuditSample> readByUser(String userId, int limit);

    /** Deletes the samples of the user's conversations. */
    long deleteByUser(String userId);

    /** Replaces the user as reviewer with a pseudonym. */
    long pseudonymizeReviewer(String userId, String pseudonym);
}
