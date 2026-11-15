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
     * Like {@link #get}, but answered by the bucket's stream leader rather than by
     * whichever replica is nearest. For admin decisions taken on a revision (a
     * force-release compares and deletes at it): a replica can answer a read with
     * an older revision than the leader holds.
     */
    default Optional<Versioned> getConsistent(String key) {
        return get(key);
    }

    /**
     * The last record of a key as the bucket's stream leader holds it: its live
     * value, or the revision of the delete marker that removed it.
     * <p>
     * {@code live == null && revision == 0} means nothing is known — the key was
     * never written, its record aged out, or the store keeps no delete markers.
     * {@code live == null && revision > 0} is a deleted key: an
     * {@link #update(String, byte[], long)} at that revision recreates it, and
     * fails if anybody wrote the key since — the same guarantee as {@link #create},
     * in one round trip instead of the three a NATS {@code create} needs on a
     * deleted key.
     */
    record Latest(Versioned live, long revision) {

        public boolean isLive() {
            return live != null;
        }
    }

    /**
     * The key's {@link Latest} record. The default knows no delete markers: an
     * absent key reads as "nothing known", so the caller falls back to
     * {@link #create}.
     */
    default Latest latest(String key) {
        return get(key).map(v -> new Latest(v, v.revision())).orElse(new Latest(null, 0));
    }

    /**
     * Writes {@code value} to a key that holds no live value: at the delete marker
     * {@code latest} names, or by {@link #create} when it names none.
     *
     * @return the new revision, or empty when somebody wrote the key in between
     */
    default OptionalLong createAt(String key, byte[] value, Latest latest) {
        return latest.revision() > 0 ? update(key, value, latest.revision()) : create(key, value);
    }

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

    /**
     * Like {@link #delete(String, long)}, but answers the revision of the delete
     * marker it wrote, so whoever acquires the key next can recreate it with one
     * {@link #update} at that revision. {@code 0} when the store does not tell.
     *
     * @return empty on a conflict (somebody wrote the key since)
     */
    default OptionalLong deleteReturningRevision(String key, long expectedRevision) {
        return delete(key, expectedRevision) ? OptionalLong.of(0) : OptionalLong.empty();
    }

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
