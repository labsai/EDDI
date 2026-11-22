/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import java.util.List;

/**
 * Day-bucketed document spend: one counter per (subject kind, subject, UTC
 * day), holding the dollars charged and the number of charges.
 * <p>
 * <b>Every write is one atomic increment</b> on the database ({@code $inc}
 * upsert on MongoDB, {@code INSERT … ON CONFLICT DO UPDATE} on PostgreSQL), so
 * concurrent charges on any node add up exactly; nothing reads a counter to
 * write it back. Reads ({@link #spent}) serve the admission check only.
 * <p>
 * <b>Retention</b> is applied by the store on write: {@link #add} deletes the
 * same subject's buckets older than the oldest retained day and, at most every
 * {@link #SWEEP_INTERVAL_MILLIS} per store instance, every subject's
 * ({@link #deleteBefore}). MongoDB also keeps a TTL index as a backstop.
 * <p>
 * Methods throw {@link DocumentSpendStoreException} when the database cannot
 * answer; callers fail closed on admission and log on charging.
 */
public interface IDocumentSpendStore {

    /** How often, at most, a store sweeps every subject's expired buckets. */
    long SWEEP_INTERVAL_MILLIS = 10 * 60 * 1000L;

    /** What a counter counts against. */
    enum SubjectKind {
        /**
         * A user, by the user id a conversation carries. Personal data: erased with the
         * user.
         */
        USER,
        /**
         * A team space ({@code team:<path>}). Holds no user id and survives a member's
         * erasure.
         */
        TEAM
    }

    /** One day's counter. */
    record DaySpend(SubjectKind kind, String subject, long epochDay, double spentUsd, long calls) {
    }

    /**
     * Adds {@code usd} and one call to the subject's bucket for {@code epochDay},
     * creating it if needed, and deletes the subject's buckets before
     * {@code oldestRetainedDay}.
     *
     * @param usd
     *            dollars to add; a non-finite or negative value adds nothing (the
     *            call is still counted)
     * @return the bucket after the increment
     */
    DaySpend add(SubjectKind kind, String subject, long epochDay, double usd, long oldestRetainedDay);

    /** Dollars charged to the subject on {@code epochDay}; 0 without a bucket. */
    double spent(SubjectKind kind, String subject, long epochDay);

    /** Deletes every bucket of the subject; returns how many. */
    long deleteBySubject(SubjectKind kind, String subject);

    /** The subject's buckets, newest first, at most {@code max}. */
    List<DaySpend> findBySubject(SubjectKind kind, String subject, int max);

    /** Deletes every subject's buckets before {@code oldestRetainedDay}. */
    long deleteBefore(long oldestRetainedDay);
}
