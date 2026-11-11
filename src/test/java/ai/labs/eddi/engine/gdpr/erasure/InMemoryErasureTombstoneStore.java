/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An {@link IErasureTombstoneStore} in memory, for unit tests;
 * {@link #failReads} and {@link #failPending} simulate a store outage.
 */
public class InMemoryErasureTombstoneStore implements IErasureTombstoneStore {

    private record Tombstone(Instant erasedAt, Instant expiresAt) {
    }

    private final Map<String, Tombstone> tombstones = new ConcurrentHashMap<>();
    private final Map<String, PendingRemoval> pending = new LinkedHashMap<>();
    private String salt;
    public volatile boolean failReads;
    public volatile boolean failPending;
    public volatile boolean failWrites;

    private static String id(Kind kind, String key) {
        return kind + ":" + key;
    }

    @Override
    public void putTombstones(Kind kind, Collection<String> keys, Instant erasedAt, Instant expiresAt) {
        if (failWrites) {
            throw new IllegalStateException("tombstone store down");
        }
        for (String key : keys) {
            tombstones.merge(id(kind, key), new Tombstone(erasedAt, expiresAt),
                    (a, b) -> new Tombstone(max(a.erasedAt(), b.erasedAt()), max(a.expiresAt(), b.expiresAt())));
        }
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    @Override
    public Optional<Instant> erasedAt(Kind kind, String key, Instant now) {
        if (failReads) {
            throw new IllegalStateException("tombstone store down");
        }
        Tombstone t = tombstones.get(id(kind, key));
        return t == null || !t.expiresAt().isAfter(now) ? Optional.empty() : Optional.of(t.erasedAt());
    }

    @Override
    public long deleteExpiredTombstones(Instant now) {
        int before = tombstones.size();
        tombstones.values().removeIf(t -> !t.expiresAt().isAfter(now));
        return before - tombstones.size();
    }

    @Override
    public synchronized String salt(String candidate) {
        if (salt == null) {
            salt = candidate;
        }
        return salt;
    }

    @Override
    public synchronized void addPendingRemoval(PendingRemoval removal) {
        if (failPending) {
            throw new IllegalStateException("pending store down");
        }
        pending.putIfAbsent(removal.writer() + ":" + removal.rowId(), removal);
    }

    @Override
    public synchronized List<PendingRemoval> pendingRemovals(String writer, String userKey, String conversationId, int limit) {
        List<PendingRemoval> found = new ArrayList<>();
        for (PendingRemoval p : pending.values()) {
            if (matches(p, writer, userKey, conversationId)) {
                found.add(p);
            }
        }
        found.sort(Comparator.comparing(PendingRemoval::createdAt));
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    private static boolean matches(PendingRemoval p, String writer, String userKey, String conversationId) {
        if (writer != null && !writer.equals(p.writer())) {
            return false;
        }
        if (userKey == null && conversationId == null) {
            return true;
        }
        return (userKey != null && userKey.equals(p.userKey())) || (conversationId != null && conversationId.equals(p.conversationId()));
    }

    @Override
    public synchronized long countPendingRemovals(String writer, String userKey, String conversationId) {
        return pending.values().stream().filter(p -> matches(p, writer, userKey, conversationId)).count();
    }

    @Override
    public synchronized boolean removePendingRemoval(String writer, String rowId) {
        return pending.remove(writer + ":" + rowId) != null;
    }

    public boolean hasTombstone(Kind kind, String key) {
        return tombstones.containsKey(id(kind, key));
    }

    public int tombstoneCount() {
        return tombstones.size();
    }

    public synchronized int pendingCount() {
        return pending.size();
    }
}
