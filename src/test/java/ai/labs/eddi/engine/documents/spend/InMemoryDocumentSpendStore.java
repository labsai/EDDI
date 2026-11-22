/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.spend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An in-memory {@link IDocumentSpendStore} for unit tests, held to the same
 * {@link DocumentSpendStoreContract} as the real stores.
 */
public class InMemoryDocumentSpendStore implements IDocumentSpendStore {

    private record Key(SubjectKind kind, String subject, long day) {
    }

    private final Map<Key, DaySpend> buckets = new ConcurrentHashMap<>();

    @Override
    public DaySpend add(SubjectKind kind, String subject, long epochDay, double usd, long oldestRetainedDay) {
        double amount = Double.isFinite(usd) && usd > 0.0 ? usd : 0.0;
        DaySpend after = buckets.merge(new Key(kind, subject, epochDay), new DaySpend(kind, subject, epochDay, amount, 1L),
                (held, added) -> new DaySpend(kind, subject, epochDay, held.spentUsd() + added.spentUsd(), held.calls() + 1L));
        buckets.keySet().removeIf(key -> key.kind() == kind && key.subject().equals(subject) && key.day() < oldestRetainedDay);
        return after;
    }

    @Override
    public double spent(SubjectKind kind, String subject, long epochDay) {
        DaySpend held = buckets.get(new Key(kind, subject, epochDay));
        return held == null ? 0.0 : held.spentUsd();
    }

    @Override
    public long deleteBySubject(SubjectKind kind, String subject) {
        List<Key> matching = buckets.keySet().stream().filter(key -> key.kind() == kind && key.subject().equals(subject)).toList();
        matching.forEach(buckets::remove);
        return matching.size();
    }

    @Override
    public List<DaySpend> findBySubject(SubjectKind kind, String subject, int max) {
        List<DaySpend> found = new ArrayList<>(
                buckets.values().stream().filter(day -> day.kind() == kind && day.subject().equals(subject)).toList());
        found.sort(Comparator.comparingLong(DaySpend::epochDay).reversed());
        return found.subList(0, Math.min(Math.max(0, max), found.size()));
    }

    @Override
    public long deleteBefore(long oldestRetainedDay) {
        List<Key> matching = buckets.keySet().stream().filter(key -> key.day() < oldestRetainedDay).toList();
        matching.forEach(buckets::remove);
        return matching.size();
    }
}
