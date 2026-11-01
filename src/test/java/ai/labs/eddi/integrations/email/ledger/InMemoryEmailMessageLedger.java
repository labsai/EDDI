/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link IEmailMessageLedger} in memory, for unit tests of its users. Same
 * semantics as the stores (both unique keys, claim by lease, token-guarded
 * changes), synchronized instead of conditional updates; the real stores are
 * held to the same contract by {@link EmailMessageLedgerContract}.
 */
public class InMemoryEmailMessageLedger implements IEmailMessageLedger {

    private final Map<String, LedgerEntry> rows = new LinkedHashMap<>();

    @Override
    public synchronized boolean insertIfAbsent(LedgerEntry entry) {
        for (LedgerEntry existing : rows.values()) {
            if (existing.idempotencyKey().equals(entry.idempotencyKey()) || (existing.integrationId().equals(entry.integrationId())
                    && existing.direction() == entry.direction() && existing.messageId().equals(entry.messageId()))) {
                return false;
            }
        }
        rows.put(entry.id(), entry);
        return true;
    }

    @Override
    public synchronized Optional<LedgerEntry> findById(String id) {
        return Optional.ofNullable(rows.get(id));
    }

    @Override
    public synchronized Optional<LedgerEntry> findByIdempotencyKey(String idempotencyKey) {
        return rows.values().stream().filter(r -> r.idempotencyKey().equals(idempotencyKey)).findFirst();
    }

    @Override
    public synchronized List<LedgerEntry> claimDue(Kind kind, Instant now, Instant leaseUntil, int limit) {
        List<LedgerEntry> due = rows.values().stream()
                .filter(r -> r.state() == State.PENDING && r.kind() == kind && r.nextAttemptAt() != null && !r.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(LedgerEntry::nextAttemptAt)).limit(limit).toList();
        List<LedgerEntry> claimed = new ArrayList<>();
        for (LedgerEntry r : due) {
            LedgerEntry next = new LedgerEntry(r.id(), r.integrationId(), r.kind(), r.direction(), r.messageId(), r.conversationId(), r.sourceRef(),
                    r.idempotencyKey(), r.recipient(), r.state(), r.attempts() + 1, leaseUntil, UUID.randomUUID().toString(), r.providerId(),
                    r.lastError(), r.createdAt(), now, r.expiresAt());
            rows.put(r.id(), next);
            claimed.add(next);
        }
        return claimed;
    }

    @Override
    public synchronized boolean complete(String id, String claimToken, State terminal, String providerId, String lastError, Instant now) {
        LedgerEntry r = rows.get(id);
        if (r == null || r.state() != State.PENDING || claimToken == null || !claimToken.equals(r.claimToken())) {
            return false;
        }
        rows.put(id, new LedgerEntry(r.id(), r.integrationId(), r.kind(), r.direction(), r.messageId(), r.conversationId(), r.sourceRef(),
                r.idempotencyKey(), r.recipient(), terminal, r.attempts(), r.nextAttemptAt(), null, providerId, lastError, r.createdAt(), now,
                r.expiresAt()));
        return true;
    }

    @Override
    public synchronized boolean reschedule(String id, String claimToken, Instant nextAttemptAt, String lastError, Instant now) {
        LedgerEntry r = rows.get(id);
        if (r == null || r.state() != State.PENDING || claimToken == null || !claimToken.equals(r.claimToken())) {
            return false;
        }
        rows.put(id, new LedgerEntry(r.id(), r.integrationId(), r.kind(), r.direction(), r.messageId(), r.conversationId(), r.sourceRef(),
                r.idempotencyKey(), r.recipient(), r.state(), r.attempts(), nextAttemptAt, null, r.providerId(), lastError, r.createdAt(), now,
                r.expiresAt()));
        return true;
    }

    @Override
    public synchronized long countCreatedSince(String recipient, Kind kind, Instant since) {
        return rows.values().stream().filter(r -> recipient.equals(r.recipient()) && r.kind() == kind && !r.createdAt().isBefore(since)
                && r.state() != State.SUPPRESSED).count();
    }

    @Override
    public synchronized long countPending() {
        return rows.values().stream().filter(r -> r.state() == State.PENDING).count();
    }

    @Override
    public synchronized long deleteExpired(Instant now) {
        int before = rows.size();
        rows.values().removeIf(r -> !r.expiresAt().isAfter(now));
        return before - rows.size();
    }

    @Override
    public synchronized List<LedgerEntry> findByRecipient(String recipient) {
        return rows.values().stream().filter(r -> recipient.equals(r.recipient())).toList();
    }

    @Override
    public synchronized long deleteByRecipient(String recipient) {
        int before = rows.size();
        rows.values().removeIf(r -> recipient.equals(r.recipient()));
        return before - rows.size();
    }

    /** Every row, for assertions. */
    public synchronized List<LedgerEntry> all() {
        return List.copyOf(rows.values());
    }
}
