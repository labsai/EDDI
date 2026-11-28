/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double with the store contract's atomicity (one lock around each
 * operation), a switch to simulate an outage, and a round-trip counter.
 */
public class InMemoryTeamBudgetStore implements ITeamBudgetStore {

    private static final class Row {
        double spent;
        double adjustment;
        final List<String> transfers = new ArrayList<>();
        final List<TeamBudgetMonth.Adjustment> history = new ArrayList<>();
        Instant expiresAt;
    }

    private final Map<String, Row> rows = new HashMap<>();
    public volatile boolean down;
    public final AtomicInteger calls = new AtomicInteger();

    private static String key(String group, String month) {
        return group + ":" + month;
    }

    private void check() {
        calls.incrementAndGet();
        if (down) {
            throw new TeamBudgetAccountingUnavailableException("store down");
        }
    }

    @Override
    public synchronized double addSpend(String groupId, String month, double usd, Instant expiresAt) {
        check();
        Row row = rows.computeIfAbsent(key(groupId, month), k -> new Row());
        row.expiresAt = expiresAt;
        row.spent += usd;
        return row.spent;
    }

    private static TeamBudgetMonth view(String key, Row row) {
        int split = key.lastIndexOf(':');
        return new TeamBudgetMonth(key.substring(0, split), key.substring(split + 1), row.spent, row.adjustment, row.transfers, row.history);
    }

    @Override
    public synchronized Optional<TeamBudgetMonth> read(String groupId, String month) {
        check();
        Row row = rows.get(key(groupId, month));
        return row == null ? Optional.empty() : Optional.of(view(key(groupId, month), row));
    }

    @Override
    public synchronized List<TeamBudgetMonth> readMany(Collection<String> groupIds, String month) {
        check();
        List<TeamBudgetMonth> found = new ArrayList<>();
        for (String g : groupIds) {
            Row row = rows.get(key(g, month));
            if (row != null) {
                found.add(view(key(g, month), row));
            }
        }
        return found;
    }

    @Override
    public synchronized List<TeamBudgetMonth> history(String groupId, int months) {
        check();
        List<TeamBudgetMonth> found = new ArrayList<>();
        rows.forEach((k, row) -> {
            if (k.startsWith(groupId + ":")) {
                found.add(view(k, row));
            }
        });
        found.sort(Comparator.comparing(TeamBudgetMonth::month).reversed());
        return found.size() > months ? found.subList(0, months) : found;
    }

    @Override
    public synchronized AdjustOutcome applyAdjustment(String groupId, String month, double deltaUsd, double baseUsd,
                                                      TeamBudgetMonth.Adjustment entry, String transferId, Instant expiresAt) {
        check();
        Row row = rows.computeIfAbsent(key(groupId, month), k -> new Row());
        if (row.expiresAt == null) {
            row.expiresAt = expiresAt;
        }
        if (transferId != null && row.transfers.contains(transferId)) {
            return AdjustOutcome.DUPLICATE;
        }
        if (deltaUsd < 0.0 && !(baseUsd + row.adjustment + deltaUsd >= row.spent)) {
            return AdjustOutcome.REFUSED;
        }
        row.adjustment += deltaUsd;
        row.history.add(entry);
        if (row.history.size() > MAX_ADJUSTMENTS) {
            row.history.remove(0);
        }
        if (transferId != null) {
            row.transfers.add(transferId);
            if (row.transfers.size() > MAX_TRANSFER_IDS) {
                row.transfers.remove(0);
            }
        }
        return AdjustOutcome.APPLIED;
    }

    @Override
    public synchronized AdjustOutcome setAdjustment(String groupId, String month, double expectedAdjustmentUsd, double newAdjustmentUsd,
                                                    double baseUsd, TeamBudgetMonth.Adjustment entry, Instant expiresAt) {
        check();
        Row row = rows.computeIfAbsent(key(groupId, month), k -> new Row());
        if (row.expiresAt == null) {
            row.expiresAt = expiresAt;
        }
        if (row.adjustment != expectedAdjustmentUsd) {
            return AdjustOutcome.CONFLICT;
        }
        if (!(baseUsd + newAdjustmentUsd >= row.spent)) {
            return AdjustOutcome.REFUSED;
        }
        row.adjustment = newAdjustmentUsd;
        row.history.add(entry);
        if (row.history.size() > MAX_ADJUSTMENTS) {
            row.history.remove(0);
        }
        return AdjustOutcome.APPLIED;
    }

    @Override
    public synchronized int deleteExpired(Instant now) {
        check();
        int before = rows.size();
        rows.values().removeIf(r -> r.expiresAt != null && r.expiresAt.isBefore(now));
        return before - rows.size();
    }
}
