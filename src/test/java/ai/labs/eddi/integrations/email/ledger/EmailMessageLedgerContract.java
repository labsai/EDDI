/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour both ledger stores must share, run against a real database by
 * {@link MongoEmailMessageLedgerIT} and {@link PostgresEmailMessageLedgerIT}:
 * the persistence boundary is where exactly-once is decided, so it is tested
 * there and not against a mock.
 */
interface EmailMessageLedgerContract {

    /** Every field a row may store — and no body, subject or MIME. */
    Set<String> ALLOWED_FIELDS = Set.of("id", "integrationid", "kind", "direction", "messageid", "conversationid", "sourceref",
            "idempotencykey", "recipient", "state", "attempts", "nextattemptat", "claimtoken", "providerid", "lasterror", "createdat",
            "updatedat", "expiresat");

    IEmailMessageLedger ledger();

    /** Empties the store between tests. */
    void clear() throws Exception;

    /**
     * The field (column) names the store actually holds, lower-cased with
     * underscores removed.
     */
    Set<String> storedFieldNames() throws Exception;

    @BeforeEach
    default void reset() throws Exception {
        clear();
    }

    Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    static LedgerEntry entry(String idempotencyKey, Instant nextAttemptAt, String recipient) {
        return new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.NOTIFY, Direction.OUT,
                "<eddi." + UUID.randomUUID() + "@example.com>", "conv-1", "pause-1", idempotencyKey, recipient, State.PENDING, 0, nextAttemptAt, null,
                null, null, NOW, NOW, NOW.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("a second row with the same idempotency key, or the same message id, is refused")
    default void uniqueKeys() {
        LedgerEntry first = entry("key-1", NOW, "a@example.com");
        assertTrue(ledger().insertIfAbsent(first));
        assertFalse(ledger().insertIfAbsent(entry("key-1", NOW, "a@example.com")), "same idempotency key");
        LedgerEntry sameMessage = new LedgerEntry(UUID.randomUUID().toString(), first.integrationId(), Kind.NOTIFY, Direction.OUT, first.messageId(),
                "conv-2", "pause-2", "key-2", "b@example.com", State.PENDING, 0, NOW, null, null, null, NOW, NOW, NOW.plus(Duration.ofDays(1)));
        assertFalse(ledger().insertIfAbsent(sameMessage), "same (integration, direction, messageId)");
        assertEquals(1, ledger().countPending());
        assertEquals("a@example.com", ledger().findByIdempotencyKey("key-1").orElseThrow().recipient());
    }

    @Test
    @DisplayName("of many concurrent inserts of one notification, exactly one wins")
    default void concurrentInsert() throws Exception {
        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return ledger().insertIfAbsent(entry("contended", NOW, "a@example.com"));
                }));
            }
            start.countDown();
            int winners = 0;
            for (Future<Boolean> result : results) {
                winners += result.get() ? 1 : 0;
            }
            assertEquals(1, winners);
            assertEquals(1, ledger().countPending());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("two sweepers claiming at once never claim the same row")
    default void claimIsExclusive() throws Exception {
        for (int i = 0; i < 20; i++) {
            ledger().insertIfAbsent(entry("due-" + i, NOW.minusSeconds(5), "a@example.com"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Callable<List<LedgerEntry>> pod = () -> {
                start.await();
                List<LedgerEntry> all = new ArrayList<>();
                List<LedgerEntry> batch;
                while (!(batch = ledger().claimDue(Kind.NOTIFY, NOW, NOW.plusSeconds(60), 3)).isEmpty()) {
                    all.addAll(batch);
                }
                return all;
            };
            Future<List<LedgerEntry>> a = pool.submit(pod);
            Future<List<LedgerEntry>> b = pool.submit(pod);
            start.countDown();
            Set<String> idsA = ids(a.get());
            Set<String> idsB = ids(b.get());
            Set<String> overlap = new HashSet<>(idsA);
            overlap.retainAll(idsB);
            assertTrue(overlap.isEmpty(), "claimed by both: " + overlap);
            assertEquals(20, idsA.size() + idsB.size());
            for (LedgerEntry claimed : a.get()) {
                assertEquals(1, claimed.attempts());
                assertEquals(NOW.plusSeconds(60), claimed.nextAttemptAt());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static Set<String> ids(List<LedgerEntry> entries) {
        Set<String> ids = new HashSet<>();
        for (LedgerEntry entry : entries) {
            ids.add(entry.id());
        }
        return ids;
    }

    @Test
    @DisplayName("a claimed row is leased: not due again until the lease ends, and a stale claim cannot finish it")
    default void leaseAndStaleClaim() {
        ledger().insertIfAbsent(entry("leased", NOW.minusSeconds(1), "a@example.com"));
        LedgerEntry first = ledger().claimDue(Kind.NOTIFY, NOW, NOW.plusSeconds(30), 10).get(0);

        assertTrue(ledger().claimDue(Kind.NOTIFY, NOW.plusSeconds(10), NOW.plusSeconds(40), 10).isEmpty(), "still leased");

        LedgerEntry second = ledger().claimDue(Kind.NOTIFY, NOW.plusSeconds(31), NOW.plusSeconds(90), 10).get(0);
        assertEquals(2, second.attempts());
        assertFalse(ledger().complete(first.id(), first.claimToken(), State.SENT, "250 ok", null, NOW.plusSeconds(32)),
                "the first claim was taken over");
        assertFalse(ledger().reschedule(first.id(), first.claimToken(), NOW.plusSeconds(100), "x", NOW.plusSeconds(32)));
        assertTrue(ledger().complete(second.id(), second.claimToken(), State.SENT, "250 ok", null, NOW.plusSeconds(32)));

        LedgerEntry done = ledger().findById(first.id()).orElseThrow();
        assertEquals(State.SENT, done.state());
        assertEquals("250 ok", done.providerId());
        assertNull(done.claimToken());
        assertEquals(0, ledger().countPending());
        assertFalse(ledger().complete(second.id(), second.claimToken(), State.FAILED, null, "late", NOW.plusSeconds(33)),
                "a terminal row does not change again");
    }

    @Test
    @DisplayName("a sweeper claims only its own kind")
    default void claimByKind() {
        LedgerEntry reply = new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.REPLY, Direction.OUT,
                "<reply@example.com>", "c", "step-1", "reply-key", "a@example.com", State.PENDING, 0, NOW.minusSeconds(1), null, null, null, NOW,
                NOW, NOW.plus(Duration.ofDays(1)));
        ledger().insertIfAbsent(reply);

        assertTrue(ledger().claimDue(Kind.NOTIFY, NOW, NOW.plusSeconds(30), 10).isEmpty());
        assertEquals(1, ledger().claimDue(Kind.REPLY, NOW, NOW.plusSeconds(30), 10).size());
    }

    @Test
    @DisplayName("reschedule releases the claim for a later attempt")
    default void reschedule() {
        ledger().insertIfAbsent(entry("retry", NOW.minusSeconds(1), "a@example.com"));
        LedgerEntry claimed = ledger().claimDue(Kind.NOTIFY, NOW, NOW.plusSeconds(30), 10).get(0);

        assertTrue(ledger().reschedule(claimed.id(), claimed.claimToken(), NOW.plusSeconds(120), "CONNECT: refused", NOW));

        LedgerEntry after = ledger().findById(claimed.id()).orElseThrow();
        assertEquals(State.PENDING, after.state());
        assertNull(after.claimToken());
        assertEquals("CONNECT: refused", after.lastError());
        assertTrue(ledger().claimDue(Kind.NOTIFY, NOW.plusSeconds(119), NOW.plusSeconds(200), 10).isEmpty());
        assertEquals(1, ledger().claimDue(Kind.NOTIFY, NOW.plusSeconds(120), NOW.plusSeconds(200), 10).size());
    }

    @Test
    @DisplayName("an oversized error or relay reply is stored capped on every write, not refused with the row left claimed")
    default void oversizedFreeTextIsCapped() {
        String longError = "RENDER: " + "e".repeat(3000);
        String longReply = "250 " + "r".repeat(1000);
        ledger().insertIfAbsent(entry("cap-retry", NOW.minusSeconds(1), "a@example.com"));
        ledger().insertIfAbsent(entry("cap-done", NOW.minusSeconds(1), "b@example.com"));
        LedgerEntry inserted = new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.NOTIFY, Direction.OUT,
                "<eddi." + UUID.randomUUID() + "@example.com>", "conv-1", "pause-1", "cap-insert", "c@example.com", State.FAILED, 1, NOW, null,
                longReply, longError, NOW, NOW, NOW.plus(Duration.ofDays(1)));
        assertTrue(ledger().insertIfAbsent(inserted));
        List<LedgerEntry> claimed = ledger().claimDue(Kind.NOTIFY, NOW, NOW.plusSeconds(30), 10);
        assertEquals(2, claimed.size());

        assertTrue(ledger().reschedule(claimed.get(0).id(), claimed.get(0).claimToken(), NOW.plusSeconds(60), longError, NOW));
        assertTrue(ledger().complete(claimed.get(1).id(), claimed.get(1).claimToken(), State.FAILED, longReply, longError, NOW));

        assertEquals(longError.substring(0, IEmailMessageLedger.MAX_LAST_ERROR), ledger().findById(claimed.get(0).id()).orElseThrow().lastError());
        LedgerEntry completed = ledger().findById(claimed.get(1).id()).orElseThrow();
        assertEquals(State.FAILED, completed.state());
        assertEquals(longError.substring(0, IEmailMessageLedger.MAX_LAST_ERROR), completed.lastError());
        assertEquals(longReply.substring(0, IEmailMessageLedger.MAX_PROVIDER_ID), completed.providerId());
        LedgerEntry stored = ledger().findById(inserted.id()).orElseThrow();
        assertEquals(IEmailMessageLedger.MAX_LAST_ERROR, stored.lastError().length());
        assertEquals(IEmailMessageLedger.MAX_PROVIDER_ID, stored.providerId().length());
    }

    @Test
    @DisplayName("the cap never splits a surrogate pair")
    default void capKeepsSurrogatePairsWhole() {
        String value = "a".repeat(9) + new String(Character.toChars(0x1F600));
        assertEquals("a".repeat(9), IEmailMessageLedger.cap(value, 10));
        assertEquals(value, IEmailMessageLedger.cap(value, 11));
        assertNull(IEmailMessageLedger.cap(null, 10));
    }

    @Test
    @DisplayName("the rate-limit count covers one recipient and kind since a point in time, suppressed rows excluded")
    default void countCreatedSince() {
        Instant hourAgo = NOW.minus(Duration.ofHours(1));
        ledger().insertIfAbsent(entry("c1", NOW, "alice@example.com"));
        ledger().insertIfAbsent(entry("c2", NOW, "alice@example.com"));
        ledger().insertIfAbsent(entry("c3", NOW, "bob@example.com"));
        ledger().insertIfAbsent(new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.NOTIFY,
                Direction.OUT, "<old@example.com>", "c", "p", "c4", "alice@example.com", State.SENT, 1, null, null, null, null,
                hourAgo.minusSeconds(1), hourAgo, NOW.plus(Duration.ofDays(1))));
        ledger().insertIfAbsent(new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.NOTIFY,
                Direction.OUT, "<suppressed@example.com>", "c", "p", "c5", "alice@example.com", State.SUPPRESSED, 0, null, null, null,
                "RATE_LIMITED", NOW, NOW, NOW.plus(Duration.ofDays(1))));
        ledger().insertIfAbsent(new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.REPLY,
                Direction.OUT, "<reply2@example.com>", "c", "s", "c6", "alice@example.com", State.SENT, 1, null, null, null, null, NOW, NOW,
                NOW.plus(Duration.ofDays(1))));

        assertEquals(2, ledger().countCreatedSince("alice@example.com", Kind.NOTIFY, hourAgo));
        assertEquals(1, ledger().countCreatedSince("bob@example.com", Kind.NOTIFY, hourAgo));
        assertEquals(0, ledger().countCreatedSince("carol@example.com", Kind.NOTIFY, hourAgo));
    }

    @Test
    @DisplayName("retention removes expired rows only")
    default void retention() {
        LedgerEntry old = new LedgerEntry(UUID.randomUUID().toString(), IEmailMessageLedger.SYSTEM_INTEGRATION, Kind.NOTIFY, Direction.OUT,
                "<old@example.com>", "c", "p", "old", "a@example.com", State.SENT, 1, null, null, "250", null, NOW.minus(Duration.ofDays(31)),
                NOW.minus(Duration.ofDays(31)), NOW.minus(Duration.ofDays(1)));
        ledger().insertIfAbsent(old);
        ledger().insertIfAbsent(entry("fresh", NOW, "a@example.com"));

        assertEquals(1, ledger().deleteExpired(NOW));
        assertTrue(ledger().findById(old.id()).isEmpty());
        assertTrue(ledger().findByIdempotencyKey("fresh").isPresent());
    }

    @Test
    @DisplayName("erasure and export reach a recipient's rows and nobody else's")
    default void byRecipient() {
        ledger().insertIfAbsent(entry("r1", NOW, "alice@example.com"));
        ledger().insertIfAbsent(entry("r2", NOW, "alice@example.com"));
        ledger().insertIfAbsent(entry("r3", NOW, "bob@example.com"));

        assertEquals(2, ledger().findByRecipient("alice@example.com").size());
        assertEquals(2, ledger().deleteByRecipient("alice@example.com"));
        assertTrue(ledger().findByRecipient("alice@example.com").isEmpty());
        assertEquals(1, ledger().findByRecipient("bob@example.com").size());
    }

    @Test
    @DisplayName("a row stores ledger facts only — no field for a body, subject or MIME exists")
    default void noBodyPersisted() throws Exception {
        ledger().insertIfAbsent(entry("shape", NOW, "a@example.com"));

        Set<String> fields = storedFieldNames();
        assertTrue(ALLOWED_FIELDS.containsAll(fields), "unexpected stored fields: " + fields);
    }

    static LedgerEntry inbound(String integrationId, String messageId, String sender, Instant createdAt) {
        return new LedgerEntry(UUID.randomUUID().toString(), integrationId, Kind.TRIGGER, Direction.IN, messageId, null, "imap:INBOX:1:1",
                "in:" + integrationId + ":" + messageId, sender, State.RECEIVED, 0, null, null, null, null, createdAt, createdAt,
                createdAt.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("inbound: one row per (integration, IN, Message-ID); other integrations and directions are separate")
    default void inboundDedup() {
        assertTrue(ledger().insertIfAbsent(inbound("int-1", "<m1@example.com>", "alice@example.com", NOW)));
        assertFalse(ledger().insertIfAbsent(inbound("int-1", "<m1@example.com>", "alice@example.com", NOW)), "redelivery of the same mail");
        assertTrue(ledger().insertIfAbsent(inbound("int-2", "<m1@example.com>", "alice@example.com", NOW)), "another mailbox received it too");

        var found = ledger().findByMessage("int-1", Direction.IN, "<m1@example.com>").orElseThrow();
        assertEquals(State.RECEIVED, found.state());
        assertEquals("alice@example.com", found.recipient());
        assertTrue(ledger().findByMessage("int-1", Direction.OUT, "<m1@example.com>").isEmpty());
        assertTrue(ledger().findOutboundByMessageId("<m1@example.com>").isEmpty(), "inbound rows are not outbound ids");
    }

    @Test
    @DisplayName("inbound: RECEIVED -> TURN_SUBMITTED -> DONE are conditional; the loser of a claim changes nothing")
    default void inboundTransitions() {
        LedgerEntry row = inbound("int-1", "<m2@example.com>", "alice@example.com", NOW);
        ledger().insertIfAbsent(row);
        Instant deadline = NOW.plus(Duration.ofMinutes(30));

        assertTrue(ledger().transition(row.id(), State.RECEIVED, State.CLAIMED, null, deadline, null, true, NOW));
        assertFalse(State.CLAIMED.isTerminal());
        assertEquals(1, ledger().findDue(Kind.TRIGGER, State.CLAIMED, deadline, 10).size(), "a stale claim is found");
        assertTrue(ledger().transition(row.id(), State.CLAIMED, State.TURN_SUBMITTED, null, deadline, null, false, NOW));
        assertFalse(ledger().transition(row.id(), State.RECEIVED, State.TURN_SUBMITTED, null, deadline, null, true, NOW), "claimed twice");
        assertTrue(ledger().transition(row.id(), State.TURN_SUBMITTED, State.TURN_SUBMITTED, "conv-9", deadline, null, false, NOW));

        var submitted = ledger().findById(row.id()).orElseThrow();
        assertEquals(State.TURN_SUBMITTED, submitted.state());
        assertEquals("conv-9", submitted.conversationId());
        assertEquals(1, submitted.attempts());
        assertEquals(deadline, submitted.nextAttemptAt());

        assertTrue(ledger().transition(row.id(), State.TURN_SUBMITTED, State.DONE, null, null, null, false, NOW));
        var done = ledger().findById(row.id()).orElseThrow();
        assertEquals(State.DONE, done.state());
        assertEquals("conv-9", done.conversationId(), "a null conversation id keeps the recorded one");
        assertNull(done.nextAttemptAt());
        assertTrue(done.state().isTerminal());
        assertFalse(State.RECEIVED.isTerminal());
        assertFalse(State.TURN_SUBMITTED.isTerminal());
        assertEquals(0, ledger().countPending(), "inbound rows are not pending outbound mail");
    }

    @Test
    @DisplayName("inbound: findDue returns submitted turns past their deadline, oldest first")
    default void strandedQuery() {
        LedgerEntry late = inbound("int-1", "<late@example.com>", "a@example.com", NOW);
        LedgerEntry later = inbound("int-1", "<later@example.com>", "a@example.com", NOW);
        LedgerEntry fresh = inbound("int-1", "<fresh@example.com>", "a@example.com", NOW);
        for (LedgerEntry row : List.of(late, later, fresh)) {
            ledger().insertIfAbsent(row);
        }
        ledger().transition(late.id(), State.RECEIVED, State.TURN_SUBMITTED, "c1", NOW.minusSeconds(120), null, true, NOW);
        ledger().transition(later.id(), State.RECEIVED, State.TURN_SUBMITTED, "c2", NOW.minusSeconds(60), null, true, NOW);
        ledger().transition(fresh.id(), State.RECEIVED, State.TURN_SUBMITTED, "c3", NOW.plusSeconds(600), null, true, NOW);

        List<LedgerEntry> due = ledger().findDue(Kind.TRIGGER, State.TURN_SUBMITTED, NOW, 10);
        assertEquals(List.of(late.id(), later.id()), due.stream().map(LedgerEntry::id).toList());
        assertEquals(1, ledger().findDue(Kind.TRIGGER, State.TURN_SUBMITTED, NOW, 1).size());
        assertTrue(ledger().findDue(Kind.NOTIFY, State.TURN_SUBMITTED, NOW, 10).isEmpty());
    }

    @Test
    @DisplayName("inbound: the per-sender count reaches only this integration's submitted or done turns since the day began")
    default void perSenderCount() {
        Instant dayStart = NOW.minus(Duration.ofHours(1));
        LedgerEntry done = inbound("int-1", "<a1@example.com>", "alice@example.com", NOW);
        LedgerEntry submitted = inbound("int-1", "<a2@example.com>", "alice@example.com", NOW);
        LedgerEntry refused = inbound("int-1", "<a3@example.com>", "alice@example.com", NOW);
        LedgerEntry received = inbound("int-1", "<a4@example.com>", "alice@example.com", NOW);
        LedgerEntry yesterday = inbound("int-1", "<a5@example.com>", "alice@example.com", NOW.minus(Duration.ofDays(1)));
        LedgerEntry otherIntegration = inbound("int-2", "<a6@example.com>", "alice@example.com", NOW);
        LedgerEntry otherSender = inbound("int-1", "<a7@example.com>", "bob@example.com", NOW);
        for (LedgerEntry row : List.of(done, submitted, refused, received, yesterday, otherIntegration, otherSender)) {
            ledger().insertIfAbsent(row);
        }
        for (LedgerEntry row : List.of(done, submitted, yesterday, otherIntegration, otherSender)) {
            ledger().transition(row.id(), State.RECEIVED, State.TURN_SUBMITTED, "c", NOW.plusSeconds(600), null, true, NOW);
        }
        ledger().transition(done.id(), State.TURN_SUBMITTED, State.DONE, null, null, null, false, NOW);
        ledger().transition(refused.id(), State.RECEIVED, State.REFUSED, null, null, "loop", false, NOW);

        assertEquals(2, ledger().countInboundTurns("int-1", "alice@example.com", dayStart));
        assertEquals(1, ledger().findByRecipient("bob@example.com").size(), "an inbound row's sender is reached by erasure and export");
    }

    @Test
    @DisplayName("outbound ids are found across integrations, for the loop guard")
    default void outboundByMessageId() {
        LedgerEntry notification = entry("loop-1", NOW, "approver@example.com");
        ledger().insertIfAbsent(notification);

        assertEquals(notification.id(), ledger().findOutboundByMessageId(notification.messageId()).orElseThrow().id());
        assertTrue(ledger().findOutboundByMessageId("<unknown@example.com>").isEmpty());
    }
}
