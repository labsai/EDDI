/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Cursor;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Direction;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.Kind;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerPage;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerQuery;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.State;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mail log reads both ledger stores must agree on —
 * {@link IEmailMessageLedger#findByIntegration} and
 * {@link IEmailMessageLedger#countInboundByOutcome} — run against MongoDB and
 * PostgreSQL by their ITs and against the in-memory fake by
 * {@link InMemoryEmailLedgerLogTest}, so the fake the unit tests use cannot
 * drift from the stores.
 */
interface EmailLedgerLogContract {

    IEmailMessageLedger ledger();

    void clear() throws Exception;

    @BeforeEach
    default void resetLog() throws Exception {
        clear();
    }

    Instant T0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    static LedgerEntry row(String integrationId, Direction direction, Kind kind, State state, String lastError, Instant createdAt) {
        String id = UUID.randomUUID().toString();
        return new LedgerEntry(id, integrationId, kind, direction, "<" + id + "@example.com>", null, "ref", "key:" + id, "alice@example.com", state,
                0, null, null, null, lastError, createdAt, createdAt, createdAt.plus(Duration.ofDays(30)));
    }

    static LedgerEntry inbound(String integrationId, State state, String lastError, Instant createdAt) {
        return row(integrationId, Direction.IN, Kind.TRIGGER, state, lastError, createdAt);
    }

    @Test
    @DisplayName("mail log: one integration's rows only, newest first")
    default void newestFirstOneIntegration() {
        LedgerEntry older = inbound("int-1", State.DONE, null, T0.minusSeconds(10));
        LedgerEntry newer = inbound("int-1", State.REFUSED, "blocked:not_allowlisted", T0);
        ledger().insertIfAbsent(older);
        ledger().insertIfAbsent(newer);
        ledger().insertIfAbsent(inbound("int-2", State.DONE, null, T0.plusSeconds(5)));

        LedgerPage page = ledger().findByIntegration("int-1", LedgerQuery.all(50));

        assertEquals(List.of(newer.id(), older.id()), page.rows().stream().map(LedgerEntry::id).toList());
        assertNull(page.nextCursor(), "everything fit on one page");
        assertEquals("blocked:not_allowlisted", page.rows().get(0).lastError());
    }

    @Test
    @DisplayName("mail log: paging over rows with equal timestamps neither repeats nor skips one")
    default void pagingOverTies() {
        List<String> expectedOrder = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            LedgerEntry r = inbound("int-1", State.DONE, null, T0);
            ledger().insertIfAbsent(r);
            expectedOrder.add(r.id());
        }
        expectedOrder.sort(Comparator.reverseOrder()); // ties: id descending (binary, as Mongo and COLLATE "C" compare)
        LedgerEntry oldest = inbound("int-1", State.DONE, null, T0.minusSeconds(1));
        ledger().insertIfAbsent(oldest);
        expectedOrder.add(oldest.id());

        List<String> seen = new ArrayList<>();
        Cursor after = null;
        int pages = 0;
        do {
            LedgerPage page = ledger().findByIntegration("int-1", new LedgerQuery(null, null, null, null, null, null, 3, after, false));
            page.rows().forEach(r -> seen.add(r.id()));
            after = page.nextCursor() == null ? null : Cursor.decode(page.nextCursor());
            pages++;
        } while (after != null && pages < 10);

        assertEquals(expectedOrder, seen, "newest first, ties by id descending, no row repeated or skipped");
        assertEquals(3, pages);
    }

    @Test
    @DisplayName("mail log: filters by direction, kind, state, reason prefix and time window")
    default void filters() {
        LedgerEntry refusedBlocked = inbound("int-1", State.REFUSED, "blocked:not_allowlisted", T0.minusSeconds(30));
        LedgerEntry refusedLoop = inbound("int-1", State.REFUSED, "loop:own_address", T0.minusSeconds(20));
        LedgerEntry unauthenticated = inbound("int-1", State.REFUSED, "unauthenticated:fail", T0.minusSeconds(10));
        LedgerEntry done = inbound("int-1", State.DONE, null, T0);
        LedgerEntry reply = row("int-1", Direction.OUT, Kind.REPLY, State.SENT, null, T0);
        for (LedgerEntry r : List.of(refusedBlocked, refusedLoop, unauthenticated, done, reply)) {
            ledger().insertIfAbsent(r);
        }

        assertEquals(Set.of(reply.id()), ids(ledger().findByIntegration("int-1", query(Direction.OUT, null, null, null, null, null))));
        assertEquals(Set.of(reply.id()), ids(ledger().findByIntegration("int-1", query(null, Kind.REPLY, null, null, null, null))));
        assertEquals(Set.of(refusedBlocked.id(), refusedLoop.id(), unauthenticated.id()),
                ids(ledger().findByIntegration("int-1", query(Direction.IN, null, State.REFUSED, null, null, null))));
        assertEquals(Set.of(refusedBlocked.id()), ids(ledger().findByIntegration("int-1", query(null, null, null, "blocked", null, null))));
        assertEquals(Set.of(refusedLoop.id()), ids(ledger().findByIntegration("int-1", query(null, null, null, "loop:own_address", null, null))));
        assertEquals(Set.of(), ids(ledger().findByIntegration("int-1", query(null, null, null, "loop_", null, null))),
                "the prefix is literal, '_' is no wildcard");
        assertEquals(Set.of(), ids(ledger().findByIntegration("int-1", query(null, null, null, "loop.", null, null))),
                "the prefix is literal, '.' is no regex wildcard");
        assertEquals(Set.of(), ids(ledger().findByIntegration("int-1", query(null, null, null, "l%", null, null))),
                "the prefix is literal, '%' is no wildcard");
        assertEquals(Set.of(refusedLoop.id(), unauthenticated.id()),
                ids(ledger().findByIntegration("int-1", query(null, null, null, null, T0.minusSeconds(20), T0))), "since inclusive, until exclusive");
        Instant justAfterDone = T0.plusNanos(500);
        assertEquals(Set.of(), ids(ledger().findByIntegration("int-1", query(Direction.IN, null, null, null, justAfterDone, null))),
                "a since finer than the store's precision does not round down onto a row");
        assertEquals(Set.of(done.id()), ids(ledger().findByIntegration("int-1", query(Direction.IN, null, State.DONE, null, null, justAfterDone))),
                "an until finer than the store's precision does not round down past a row");
    }

    @Test
    @DisplayName("mail log: a reason prefix outside the BMP matches exactly as Java's startsWith does")
    default void supplementaryPrefix() {
        LedgerEntry relay = inbound("int-1", State.REFUSED, "relay:\uD83D\uDCE7 rejected", T0);
        ledger().insertIfAbsent(relay);
        ledger().insertIfAbsent(inbound("int-1", State.REFUSED, "relay:x", T0.minusSeconds(1)));

        assertEquals(Set.of(relay.id()), ids(ledger().findByIntegration("int-1", query(null, null, null, "relay:\uD83D\uDCE7", null, null))));
        assertEquals(Set.of(relay.id()), ids(ledger().findByIntegration("int-1", query(null, null, null, "relay:\uD83D\uDCE7 ", null, null))));
    }

    @Test
    @DisplayName("mail log: signature markers are left out unless asked for")
    default void markersHidden() {
        LedgerEntry mail = inbound("int-1", State.DONE, null, T0);
        LedgerEntry marker = inbound("int-1", State.REFUSED, IEmailMessageLedger.MARKER_REASON, T0);
        ledger().insertIfAbsent(mail);
        ledger().insertIfAbsent(marker);

        assertEquals(Set.of(mail.id()), ids(ledger().findByIntegration("int-1", LedgerQuery.all(50))));
        assertEquals(Set.of(mail.id(), marker.id()),
                ids(ledger().findByIntegration("int-1", new LedgerQuery(null, null, null, null, null, null, 50, null, true))));
    }

    @Test
    @DisplayName("outcome counts: inbound rows of one integration in [since, until), by state and reason category, markers excluded")
    default void countsByOutcome() {
        Instant dayStart = T0.minus(Duration.ofDays(1));
        ledger().insertIfAbsent(inbound("int-1", State.DONE, null, dayStart));
        ledger().insertIfAbsent(inbound("int-1", State.DONE, null, dayStart.plusSeconds(60)));
        ledger().insertIfAbsent(inbound("int-1", State.REFUSED, "blocked:not_allowlisted", dayStart.plusSeconds(61)));
        ledger().insertIfAbsent(inbound("int-1", State.REFUSED, "blocked:blocklist", dayStart.plusSeconds(62)));
        ledger().insertIfAbsent(inbound("int-1", State.REFUSED, "unauthenticated:none", dayStart.plusSeconds(63)));
        ledger().insertIfAbsent(inbound("int-1", State.RECEIVED, "throttled", dayStart.plusSeconds(64)));
        ledger().insertIfAbsent(inbound("int-1", State.REFUSED, IEmailMessageLedger.MARKER_REASON, dayStart.plusSeconds(65)));
        ledger().insertIfAbsent(inbound("int-1", State.DONE, null, T0)); // until is exclusive
        ledger().insertIfAbsent(inbound("int-1", State.DONE, null, dayStart.minusMillis(1)));
        ledger().insertIfAbsent(inbound("int-2", State.DONE, null, dayStart.plusSeconds(1)));
        ledger().insertIfAbsent(row("int-1", Direction.OUT, Kind.REPLY, State.SENT, null, dayStart.plusSeconds(2)));

        Map<String, Long> counts = ledger().countInboundByOutcome("int-1", dayStart, T0);

        assertEquals(Map.of("done", 2L, "refused:blocked", 2L, "refused:unauthenticated", 1L, "deferred:throttled", 1L), counts);
    }

    @Test
    @DisplayName("cursor: round-trips; anything else is refused as malformed")
    default void cursorCodec() {
        Cursor cursor = new Cursor(T0, "abc-123");
        assertEquals(cursor, Cursor.decode(cursor.encode()));
        assertThrows(IllegalArgumentException.class, () -> Cursor.decode("not base64 !"));
        assertThrows(IllegalArgumentException.class, () -> Cursor.decode("bm8tYmFy"));
        assertThrows(IllegalArgumentException.class, () -> Cursor.decode("eHx5"));
        assertNotNull(new Cursor(T0, "x").encode());
        assertEquals("blocked", IEmailMessageLedger.reasonCategory("blocked:x"));
        assertEquals("unroutable", IEmailMessageLedger.reasonCategory("unroutable"));
        assertNull(IEmailMessageLedger.reasonCategory(null));
        assertTrue(IEmailMessageLedger.outcomeKey(State.REFUSED, null).equals("refused:unknown"));
    }

    private static LedgerQuery query(Direction direction, Kind kind, State state, String reason, Instant since, Instant until) {
        return new LedgerQuery(direction, kind, state, reason, since, until, 200, null, false);
    }

    private static Set<String> ids(LedgerPage page) {
        Set<String> ids = new HashSet<>();
        page.rows().forEach(r -> ids.add(r.id()));
        return ids;
    }
}
