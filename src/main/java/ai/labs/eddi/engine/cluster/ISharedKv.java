/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * One logical bucket of state shared by every node of the cluster — a NATS
 * JetStream KV bucket in cluster mode, a map in tests and in degraded
 * fallbacks.
 * <p>
 * Deliberately <em>not</em> a distributed {@code ICache}: {@code ICache} is a
 * full {@code ConcurrentMap} ({@code size}, iteration, bulk operations) that a
 * KV store cannot honour atomically. Everything here is a single-key operation
 * with an explicit revision, so every read-modify-write is a compare-and-set.
 * <p>
 * Every method throws {@link ClusterUnavailableException} immediately while
 * NATS is unreachable; the caller decides what its area does then (see the
 * degraded-mode table in {@code docs/clustering.md}).
 */
public interface ISharedKv {

    /**
     * A value with the revision that wrote it and, where the store knows it, when
     * that revision was written (epoch millis, the server's clock; {@code 0} when
     * unknown).
     */
    record Versioned(byte[] value, long revision, long writtenAt) {

        public Versioned(byte[] value, long revision) {
            this(value, revision, 0L);
        }
    }

    /** A live key with its value, as listed by {@link #entries}. */
    record Entry(String key, Versioned value) {
    }

    String bucket();

    /**
     * Creates {@code key} only if it does not exist (or was deleted).
     *
     * @return the new revision, or empty when the key already holds a value
     */
    OptionalLong create(String key, byte[] value);

    Optional<Versioned> get(String key);

    /**
     * Replaces the value only while the key is still at {@code expectedRevision}.
     *
     * @return the new revision, or empty on a conflict
     */
    OptionalLong update(String key, byte[] value, long expectedRevision);

    /** Unconditional write. */
    long put(String key, byte[] value);

    /**
     * Deletes only while the key is still at {@code expectedRevision}.
     *
     * @return false on a conflict (somebody wrote the key since)
     */
    boolean delete(String key, long expectedRevision);

    /** Unconditional delete; a missing key is not an error. */
    void delete(String key);

    /** The current keys (live values only). */
    List<String> keys();

    /**
     * The live entries whose key starts with {@code prefix} (every key for an empty
     * prefix), at most {@code max} of them, in no particular order. For admin
     * listings only — never on a turn's path.
     * <p>
     * The default reads key by key; the NATS bucket overrides it with one watch.
     */
    default List<Entry> entries(String prefix, int max) {
        List<Entry> entries = new ArrayList<>();
        for (String key : keys()) {
            if (entries.size() >= max) {
                break;
            }
            if (prefix == null || key.startsWith(prefix)) {
                get(key).ifPresent(v -> entries.add(new Entry(key, v)));
            }
        }
        return entries;
    }
}
