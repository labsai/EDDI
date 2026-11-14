/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange;
import ai.labs.eddi.engine.spendcontrol.model.SpendControlChange.Status;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A map-backed store that round-trips every change through the storage JSON, as
 * both real stores do.
 */
class InMemorySpendControlChangeStore implements ISpendControlChangeStore {

    final Map<String, String> rows = new ConcurrentHashMap<>();

    @Override
    public void create(SpendControlChange change) {
        if (rows.putIfAbsent(change.id(), SpendControlChangeJson.write(change)) != null) {
            throw new IllegalStateException("duplicate id");
        }
    }

    @Override
    public Optional<SpendControlChange> read(String id) {
        String json = rows.get(id);
        return json == null ? Optional.empty() : Optional.of(SpendControlChangeJson.read(json));
    }

    @Override
    public List<SpendControlChange> list(Status status, int limit) {
        return rows.values().stream().map(SpendControlChangeJson::read).filter(c -> status == null || c.status() == status)
                .sorted(Comparator.comparingLong(SpendControlChange::proposedAtMillis).reversed()).limit(limit).toList();
    }

    @Override
    public long count(Status status) {
        return list(status, Integer.MAX_VALUE).size();
    }

    @Override
    public synchronized boolean compareAndSetStatus(String id, Status expected, SpendControlChange next) {
        Optional<SpendControlChange> current = read(id);
        if (current.isEmpty() || current.get().status() != expected) {
            return false;
        }
        rows.put(id, SpendControlChangeJson.write(next));
        return true;
    }

    @Override
    public long deleteDecidedBefore(long proposedBeforeMillis) {
        List<String> doomed = list(null, Integer.MAX_VALUE).stream()
                .filter(c -> c.status() != Status.PENDING && c.status() != Status.APPLYING && c.proposedAtMillis() < proposedBeforeMillis)
                .map(SpendControlChange::id).toList();
        doomed.forEach(rows::remove);
        return doomed.size();
    }
}
