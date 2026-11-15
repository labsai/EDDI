/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * A process-local {@link ISharedKv} with the same create / compare-and-set /
 * expiry semantics as a NATS KV bucket: a single monotonic revision counter for
 * the whole bucket, and a value that is not rewritten within the TTL vanishes.
 * <p>
 * Used as the degraded-mode fallback of components whose policy is
 * {@code local}, by the in-memory {@link ISharedStateFactory}, and by the unit
 * tests of every component that runs against a shared bucket.
 */
public class InMemorySharedKv implements ISharedKv {

    /** A value, or a delete marker when {@code value} is {@code null}. */
    private record Entry(byte[] value, long revision, long writtenAtMillis) {
    }

    private final String bucket;
    private final long ttlMillis;
    private final LongSupplier clock;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private long lastRevision;

    public InMemorySharedKv(String bucket, Duration ttl) {
        this(bucket, ttl, System::currentTimeMillis);
    }

    public InMemorySharedKv(String bucket, Duration ttl, LongSupplier clock) {
        this.bucket = bucket;
        this.ttlMillis = ttl == null || ttl.isZero() ? Long.MAX_VALUE : ttl.toMillis();
        this.clock = clock;
    }

    @Override
    public String bucket() {
        return bucket;
    }

    /** The key's last record — a value or a delete marker — within the TTL. */
    private Entry current(String key) {
        Entry e = entries.get(key);
        if (e != null && clock.getAsLong() - e.writtenAtMillis() >= ttlMillis) {
            entries.remove(key, e);
            return null;
        }
        return e;
    }

    private Entry live(String key) {
        Entry e = current(key);
        return e == null || e.value() == null ? null : e;
    }

    /**
     * Deletes the key and returns the revision of the delete marker. Like a NATS
     * bucket, the marker is kept until it ages out — but only in a bucket with a
     * TTL: without one, a map of every key ever deleted would only grow, so the key
     * is simply forgotten there.
     */
    private long tombstone(String key) {
        long rev = ++lastRevision;
        if (ttlMillis == Long.MAX_VALUE) {
            entries.remove(key);
        } else {
            entries.put(key, new Entry(null, rev, clock.getAsLong()));
        }
        return rev;
    }

    @Override
    public synchronized OptionalLong create(String key, byte[] value) {
        if (live(key) != null) {
            return OptionalLong.empty();
        }
        long rev = ++lastRevision;
        entries.put(key, new Entry(value.clone(), rev, clock.getAsLong()));
        return OptionalLong.of(rev);
    }

    @Override
    public synchronized Optional<Versioned> get(String key) {
        Entry e = live(key);
        return e == null ? Optional.empty() : Optional.of(new Versioned(e.value().clone(), e.revision(), e.writtenAtMillis()));
    }

    @Override
    public synchronized Latest latest(String key) {
        Entry e = current(key);
        if (e == null) {
            return new Latest(null, 0);
        }
        if (e.value() == null) {
            return new Latest(null, e.revision());
        }
        return new Latest(new Versioned(e.value().clone(), e.revision(), e.writtenAtMillis()), e.revision());
    }

    /**
     * Compare-and-set on the key's last record, as NATS does it: a delete marker's
     * revision is a valid expectation too.
     */
    @Override
    public synchronized OptionalLong update(String key, byte[] value, long expectedRevision) {
        Entry e = current(key);
        if (e == null || e.revision() != expectedRevision) {
            return OptionalLong.empty();
        }
        long rev = ++lastRevision;
        entries.put(key, new Entry(value.clone(), rev, clock.getAsLong()));
        return OptionalLong.of(rev);
    }

    @Override
    public synchronized long put(String key, byte[] value) {
        long rev = ++lastRevision;
        entries.put(key, new Entry(value.clone(), rev, clock.getAsLong()));
        return rev;
    }

    @Override
    public synchronized boolean delete(String key, long expectedRevision) {
        return deleteReturningRevision(key, expectedRevision).isPresent();
    }

    @Override
    public synchronized OptionalLong deleteReturningRevision(String key, long expectedRevision) {
        Entry e = live(key);
        if (e == null || e.revision() != expectedRevision) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(tombstone(key));
    }

    @Override
    public synchronized void delete(String key) {
        if (live(key) != null) {
            tombstone(key);
        }
    }

    @Override
    public synchronized List<String> keys() {
        List<String> keys = new ArrayList<>();
        for (String key : new ArrayList<>(entries.keySet())) {
            if (live(key) != null) {
                keys.add(key);
            }
        }
        return keys;
    }
}
