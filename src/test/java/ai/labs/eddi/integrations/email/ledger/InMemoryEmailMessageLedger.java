/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
    private final Map<String, ReplyRecipient> recipients = new LinkedHashMap<>();

    /**
     * Writes a row the way the stores do: the free-text fields capped at their
     * column width.
     */
    private void store(LedgerEntry r) {
        rows.put(r.id(), new LedgerEntry(r.id(), r.integrationId(), r.kind(), r.direction(), r.messageId(), r.conversationId(), r.sourceRef(),
                r.idempotencyKey(), r.recipient(), r.state(), r.attempts(), r.nextAttemptAt(), r.claimToken(),
                IEmailMessageLedger.cap(r.providerId(), MAX_PROVIDER_ID), IEmailMessageLedger.cap(r.lastError(), MAX_LAST_ERROR), r.createdAt(),
                r.updatedAt(), r.expiresAt(), r.threadKey()));
    }

    @Override
    public synchronized boolean insertIfAbsent(LedgerEntry entry) {
        for (LedgerEntry existing : rows.values()) {
            if (existing.idempotencyKey().equals(entry.idempotencyKey()) || (existing.integrationId().equals(entry.integrationId())
                    && existing.direction() == entry.direction() && existing.messageId().equals(entry.messageId()))) {
                return false;
            }
        }
        store(entry);
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
                    r.lastError(), r.createdAt(), now, r.expiresAt(), r.threadKey());
            store(next);
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
        store(new LedgerEntry(r.id(), r.integrationId(), r.kind(), r.direction(), r.messageId(), r.conversationId(), r.sourceRef(),
                r.idempotencyKey(), r.recipient(), terminal, r.attempts(), r.nextAttemptAt(), null, providerId, lastError, r.createdAt(), now,
                r.expiresAt(), r.threadKey()));
        return true;
    }

    @Override
    public synchronized boolean reschedule(String id, String claimToken, Instant nextAttemptAt, String lastError, Instant now) {
        LedgerEntry r = rows.get(id);
        if (r == null || r.state() != State.PENDING || claimToken == null || !claimToken.equals(r.claimToken())) {
            return false;
        }
        store(new LedgerEntry(r.id(), r.integrationId(), r.kind(), r.direction(), r.messageId(), r.conversationId(), r.sourceRef(),
                r.idempotencyKey(), r.recipient(), r.state(), r.attempts(), nextAttemptAt, null, r.providerId(), lastError, r.createdAt(), now,
                r.expiresAt(), r.threadKey()));
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
        recipients.values().removeIf(r -> !r.expiresAt().isAfter(now));
        int before = rows.size();
        rows.values().removeIf(r -> !r.expiresAt().isAfter(now));
        return before - rows.size();
    }

    @Override
    public synchronized Optional<LedgerEntry> findByMessage(String integrationId, Direction direction, String messageId) {
        return rows.values().stream()
                .filter(r -> r.integrationId().equals(integrationId) && r.direction() == direction && r.messageId().equals(messageId)).findFirst();
    }

    @Override
    public synchronized Optional<LedgerEntry> findOutboundByMessageId(String messageId) {
        return rows.values().stream().filter(r -> r.direction() == Direction.OUT && r.messageId().equals(messageId)).findFirst();
    }

    @Override
    public synchronized boolean transition(String id, State from, State to, String conversationId, Instant nextAttemptAt, String lastError,
                                           boolean countAttempt, Instant now) {
        LedgerEntry r = rows.get(id);
        if (r == null || r.state() != from) {
            return false;
        }
        store(new LedgerEntry(r.id(), r.integrationId(), r.kind(), r.direction(), r.messageId(),
                conversationId != null ? conversationId : r.conversationId(), r.sourceRef(), r.idempotencyKey(), r.recipient(), to,
                r.attempts() + (countAttempt ? 1 : 0), nextAttemptAt, r.claimToken(), r.providerId(), lastError, r.createdAt(), now, r.expiresAt(),
                r.threadKey()));
        return true;
    }

    @Override
    public synchronized List<LedgerEntry> findDue(Kind kind, State state, Instant now, int limit) {
        return rows.values().stream()
                .filter(r -> r.state() == state && r.kind() == kind && r.nextAttemptAt() != null && !r.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(LedgerEntry::nextAttemptAt)).limit(limit).toList();
    }

    @Override
    public synchronized List<LedgerEntry> findDueResubmissions(String integrationId, Instant now, int limit) {
        return rows.values().stream()
                .filter(r -> r.integrationId().equals(integrationId) && r.state() == State.RECEIVED && r.kind() == Kind.TRIGGER
                        && r.conversationId() != null && r.nextAttemptAt() != null && !r.nextAttemptAt().isAfter(now))
                .sorted(Comparator.comparing(LedgerEntry::nextAttemptAt)).limit(limit).toList();
    }

    @Override
    public synchronized long countInboundTurns(String integrationId, String sender, Instant since) {
        return rows.values().stream()
                .filter(r -> r.integrationId().equals(integrationId) && sender.equals(r.recipient()) && r.kind() == Kind.TRIGGER
                        && !r.createdAt().isBefore(since) && (r.state() == State.TURN_SUBMITTED || r.state() == State.DONE))
                .count();
    }

    @Override
    public synchronized Optional<LedgerEntry> findInboundTurn(String conversationId, boolean latest) {
        List<LedgerEntry> turns = rows.values().stream()
                .filter(r -> conversationId.equals(r.conversationId()) && r.kind() == Kind.TRIGGER && r.direction() == Direction.IN
                        && (r.state() == State.TURN_SUBMITTED || r.state() == State.DONE))
                .sorted(Comparator.comparing(LedgerEntry::createdAt)).toList();
        return turns.isEmpty() ? Optional.empty() : Optional.of(turns.get(latest ? turns.size() - 1 : 0));
    }

    @Override
    public synchronized boolean hasSentReply(String conversationId, String recipient) {
        return rows.values().stream()
                .anyMatch(r -> conversationId.equals(r.conversationId()) && r.kind() == Kind.REPLY && r.direction() == Direction.OUT
                        && r.state() == State.SENT && (recipient.equals(r.recipient()) || recipients.values().stream()
                                .anyMatch(c -> c.rowId().equals(r.id()) && c.role() == RecipientRole.CC && recipient.equals(c.address()))));
    }

    @Override
    public synchronized void addReplyRecipients(List<ReplyRecipient> entries) {
        for (ReplyRecipient entry : entries) {
            recipients.putIfAbsent(entry.rowId() + "\n" + entry.address(), entry);
        }
    }

    @Override
    public synchronized List<ReplyRecipient> findReplyRecipients(String rowId) {
        List<ReplyRecipient> found = new ArrayList<>(recipients.values().stream().filter(r -> r.rowId().equals(rowId)).toList());
        found.sort(Comparator.comparing(r -> r.role() == RecipientRole.CC));
        return found;
    }

    @Override
    public synchronized long removeReplyRecipients(String rowId, Collection<String> addresses) {
        int before = recipients.size();
        recipients.values().removeIf(r -> r.rowId().equals(rowId) && addresses.contains(r.address()));
        return before - recipients.size();
    }

    @Override
    public synchronized List<ReplyRecipient> findReplyRecipientsByAddress(String address) {
        return recipients.values().stream().filter(r -> address.equals(r.address())).toList();
    }

    @Override
    public synchronized long countByConversationSince(String conversationId, Kind kind, Instant since) {
        return rows.values().stream().filter(r -> conversationId.equals(r.conversationId()) && r.kind() == kind && !r.createdAt().isBefore(since)
                && r.state() != State.SUPPRESSED).count();
    }

    @Override
    public synchronized long countByThreadSince(String integrationId, String threadKey, Kind kind, Instant since) {
        if (threadKey == null) {
            return 0;
        }
        return rows.values().stream().filter(r -> integrationId.equals(r.integrationId()) && threadKey.equals(r.threadKey()) && r.kind() == kind
                && !r.createdAt().isBefore(since) && r.state() != State.SUPPRESSED).count();
    }

    /**
     * Test hook: replaces a row as it is (to simulate an earlier state or a crash).
     */
    public synchronized void put(LedgerEntry entry) {
        rows.put(entry.id(), entry);
    }

    @Override
    public synchronized List<LedgerEntry> findByRecipient(String recipient) {
        return rows.values().stream().filter(r -> recipient.equals(r.recipient())).toList();
    }

    @Override
    public synchronized long deleteByRecipient(String recipient) {
        List<String> rowIds = rows.values().stream().filter(r -> recipient.equals(r.recipient())).map(LedgerEntry::id).toList();
        recipients.values().removeIf(r -> recipient.equals(r.address()) || rowIds.contains(r.rowId()));
        int before = rows.size();
        rows.values().removeIf(r -> recipient.equals(r.recipient()));
        return before - rows.size();
    }

    /** Every row, for assertions. */
    public synchronized List<LedgerEntry> all() {
        return List.copyOf(rows.values());
    }
}
