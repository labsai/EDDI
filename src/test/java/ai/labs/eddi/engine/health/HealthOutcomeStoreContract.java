/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@link IHealthOutcomeStore} contract, run against a real database by each
 * implementation's {@code *IT}. Each implementing class must hand out a store
 * over an empty collection/table (and no watermark) per test.
 */
public interface HealthOutcomeStoreContract {

    /** A store over an empty collection/table. */
    IHealthOutcomeStore store();

    Instant T0 = Instant.parse("2026-11-10T10:00:00Z");

    static HealthOutcome session(String conversation, int sessionNo, String agent, String user, Instant endedAt, String origin, boolean engaged,
                                 String outcome, String evidence, boolean provisional, Instant dueAt) {
        return new HealthOutcome(conversation, sessionNo, agent, 2, user, origin, engaged, endedAt.minusSeconds(600), endedAt, 4, 0.25, 1, outcome,
                evidence, provisional, LocalDate.ofInstant(endedAt, ZoneOffset.UTC), dueAt, 0, List.of("order_confirmed", "$weird"), T0);
    }

    static HealthOutcome closed(String conversation, int sessionNo, Instant endedAt, String outcome, String evidence) {
        return session(conversation, sessionNo, "agent", "user", endedAt, "api", true, outcome, evidence, false, null);
    }

    private static HealthUsageQuery query(LocalDate from, LocalDate to, Integer version, String... origins) {
        return new HealthUsageQuery("agent", from, to, version, List.of(origins));
    }

    @Test
    @DisplayName("upsert inserts, then replaces by (conversation, session) — every field round-trips")
    default void upsertReplaces() {
        HealthOutcome open = session("c1", 0, "agent", "user", T0, "api", true, HealthOutcomes.OPEN, null, true, T0.plusSeconds(86_400));
        store().upsert(List.of(open));
        assertEquals(List.of(open), store().findByConversation("c1"));

        HealthOutcome resolved = closed("c1", 0, T0, HealthOutcomes.ASSUMED_RESOLVED, HealthOutcomes.EVIDENCE_SILENCE);
        store().upsert(List.of(resolved));
        assertEquals(List.of(resolved), store().findByConversation("c1"), "replaced, not added");
    }

    @Test
    @DisplayName("a conversation's sessions come oldest first; deleteSessions removes only the named ones")
    default void conversationSessions() {
        store().upsert(List.of(closed("c1", 7, T0.plusSeconds(100_000), "FAILED", "structural"), closed("c1", 0, T0, "ABANDONED", "inferred"),
                closed("c2", 0, T0, "ABANDONED", "inferred")));
        assertEquals(List.of(0, 7), store().findByConversation("c1").stream().map(HealthOutcome::sessionNo).toList());
        assertEquals(1, store().deleteSessions("c1", List.of(7, 99)));
        assertEquals(List.of(0), store().findByConversation("c1").stream().map(HealthOutcome::sessionNo).toList());
        assertEquals(1, store().findByConversation("c2").size(), "another conversation's session 7 namesake is not touched");
        assertEquals(0, store().deleteSessions("c1", List.of()));
    }

    @Test
    @DisplayName("findDue returns sessions due at or before now, earliest first; markDue makes a conversation due without postponing")
    default void dueSessions() {
        store().upsert(List.of(session("c1", 0, "agent", "u", T0, "api", true, "OPEN", null, true, T0.plusSeconds(60)),
                session("c2", 0, "agent", "u", T0, "api", true, "OPEN", null, true, T0.plusSeconds(30)),
                session("c3", 0, "agent", "u", T0, "api", true, "OPEN", null, true, T0.plusSeconds(3_600)),
                closed("c4", 0, T0, "FAILED", "structural")));
        assertEquals(List.of("c2", "c1"), store().findDue(T0.plusSeconds(120), 10).stream().map(HealthOutcome::conversationId).toList());
        assertEquals(List.of("c2"), store().findDue(T0.plusSeconds(120), 1).stream().map(HealthOutcome::conversationId).toList());

        assertEquals(1, store().markDue("c4", null, T0.plusSeconds(10)));
        assertEquals(1, store().markDue("c3", List.of(0), T0.plusSeconds(10)), "an earlier due time wins");
        assertEquals(0, store().markDue("c2", null, T0.plusSeconds(50)), "a later one does not postpone");
        assertEquals(0, store().markDue("c1", List.of(5), T0.plusSeconds(1)), "only the named sessions");
        assertEquals(0, store().markDue("c1", List.of(), T0.plusSeconds(1)));
        List<String> due = store().findDue(T0.plusSeconds(120), 10).stream().map(HealthOutcome::conversationId).toList();
        assertEquals(4, due.size(), due.toString());
        assertEquals(Set.of("c3", "c4"), Set.copyOf(due.subList(0, 2)), "both due at T0+10s");
        assertEquals(List.of("c2", "c1"), due.subList(2, 4));
    }

    @Test
    @DisplayName("a due mark newer than the evaluation that writes the row survives it, as the earlier time; an older one does not")
    default void markSurvivesUpsert() {
        Instant evaluatedAt = T0.plusSeconds(100);
        HealthOutcome open = session("c1", 0, "agent", "u", T0, "api", true, "OPEN", null, true, T0.plusSeconds(86_400));
        store().upsert(List.of(open));
        // A rating arrives while the next evaluation runs (it started at evaluatedAt).
        store().markDue("c1", null, evaluatedAt.plusSeconds(5));
        HealthOutcome closed = new HealthOutcome("c1", 0, "agent", 2, "u", "api", true, T0, T0, 4, 0.25, 1, "ASSUMED_RESOLVED", "silence", false,
                open.day(), null, 0, List.of(), evaluatedAt);
        store().upsert(List.of(closed));
        assertEquals(evaluatedAt.plusSeconds(5), store().findByConversation("c1").get(0).dueAt(), "the mark is kept");

        HealthOutcome later = new HealthOutcome("c1", 0, "agent", 2, "u", "api", true, T0, T0, 4, 0.25, 1, "ASSUMED_RESOLVED", "silence", false,
                open.day(), null, 0, List.of(), evaluatedAt.plusSeconds(60));
        store().upsert(List.of(later));
        assertEquals(null, store().findByConversation("c1").get(0).dueAt(), "evaluated after the mark: cleared");
        assertTrue(store().findDue(T0.plus(Duration.ofDays(9)), 10).isEmpty());
    }

    @Test
    @DisplayName("counts group by outcome, evidence, engaged and provisional, per day and per group when asked, within the scope")
    default void counts() {
        Instant day1 = Instant.parse("2026-11-09T12:00:00Z");
        Instant day2 = Instant.parse("2026-11-10T12:00:00Z");
        store().upsert(List.of(closed("c1", 0, day1, "ASSUMED_RESOLVED", "silence"), closed("c2", 0, day2, "ASSUMED_RESOLVED", "silence"),
                closed("c3", 0, day2, "ESCALATED", "agent_action"),
                session("c4", 0, "agent", "u", day2, "email", false, "OPEN", null, true, day2.plusSeconds(3_600)),
                session("c5", 0, "agent", "u", day2, "schedule", false, null, null, false, null),
                session("c6", 0, "other", "u", day2, "api", true, "FAILED", "structural", false, null)));
        List<HealthOutcomeCount> all = store().counts(query(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), null, "api", "email"), false,
                "none");
        assertEquals(4, all.stream().mapToLong(HealthOutcomeCount::sessions).sum(), all.toString());
        assertEquals(16, all.stream().mapToLong(HealthOutcomeCount::turns).sum());
        HealthOutcomeCount silence = all.stream().filter(c -> "silence".equals(c.evidence())).findFirst().orElseThrow();
        assertEquals(2, silence.sessions());
        assertTrue(silence.engaged());
        assertNull(silence.day());
        HealthOutcomeCount open = all.stream().filter(c -> "OPEN".equals(c.outcome())).findFirst().orElseThrow();
        assertTrue(open.provisional());

        List<HealthOutcomeCount> perDay = store().counts(query(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), null, "api"), true,
                "origin");
        assertEquals(1, perDay.stream().filter(c -> LocalDate.of(2026, 11, 9).equals(c.day())).mapToLong(HealthOutcomeCount::sessions).sum());
        assertTrue(perDay.stream().allMatch(c -> "api".equals(c.group())));

        assertEquals(0, store().counts(query(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), 7, "api"), false, "none").size(),
                "another version");
        assertEquals(1, store().counts(query(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), null, "schedule"), false, "version").size());
        assertEquals("2", store().counts(query(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), null, "schedule"), false, "version").get(0)
                .group());
    }

    @Test
    @DisplayName("sessions pages newest first and filters by version, origin, outcome and evidence")
    default void drillDown() {
        List<HealthOutcome> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(closed("c" + i, 0, T0.plusSeconds(i * 60L), i % 2 == 0 ? "ASSUMED_RESOLVED" : "ABANDONED", i % 2 == 0 ? "silence" : "inferred"));
        }
        rows.add(session("e1", 0, "agent", "u", T0.plusSeconds(999), "email", true, "RESOLVED", "agent_action", false, null));
        store().upsert(rows);
        LocalDate day = LocalDate.ofInstant(T0, ZoneOffset.UTC);
        List<HealthOutcome> page = store().sessions(new HealthSessionQuery("agent", day, day, Set.of(2), List.of("api"), List.of(), List.of(), 0, 2));
        assertEquals(List.of("c4", "c3"), page.stream().map(HealthOutcome::conversationId).toList());
        List<HealthOutcome> next = store().sessions(new HealthSessionQuery("agent", day, day, Set.of(2), List.of("api"), List.of(), List.of(), 2, 2));
        assertEquals(List.of("c2", "c1"), next.stream().map(HealthOutcome::conversationId).toList());
        assertEquals(List.of("c3", "c1"), store().sessions(new HealthSessionQuery("agent", day, day, Set.of(2), List.of("api"),
                List.of("ABANDONED"), List.of(), 0, 10)).stream().map(HealthOutcome::conversationId).toList());
        assertEquals(List.of("e1"), store().sessions(new HealthSessionQuery("agent", day, day, Set.of(2), List.of("api", "email"), List.of(),
                List.of("agent_action"), 0, 10)).stream().map(HealthOutcome::conversationId).toList());
        assertTrue(store().sessions(new HealthSessionQuery("agent", day, day, Set.of(1), List.of("api"), List.of(), List.of(), 0, 10)).isEmpty(),
                "a version the caller may not review");
    }

    @Test
    @DisplayName("endingActions counts each closed, engaged, judged session's last actions once")
    default void endingActions() {
        store().upsert(List.of(closed("c1", 0, T0, "ASSUMED_RESOLVED", "silence"), closed("c2", 0, T0, "ABANDONED", "inferred"),
                session("c3", 0, "agent", "u", T0, "api", true, "OPEN", null, true, T0.plusSeconds(60)),
                session("c4", 0, "agent", "u", T0, "api", false, "ASSUMED_RESOLVED", "silence", false, null)));
        LocalDate day = LocalDate.ofInstant(T0, ZoneOffset.UTC);
        Map<String, Long> ending = store().endingActions(query(day, day, null, "api"));
        assertEquals(Map.of("order_confirmed", 2L, "$weird", 2L), ending);
    }

    @Test
    @DisplayName("portfolioSessions splits window and baseline, interactive and all")
    default void portfolio() {
        store().upsert(List.of(closed("c1", 0, Instant.parse("2026-11-01T10:00:00Z"), "FAILED", "structural"),
                closed("c2", 0, Instant.parse("2026-11-08T10:00:00Z"), "FAILED", "structural"),
                session("c3", 0, "agent", "u", Instant.parse("2026-11-09T10:00:00Z"), "schedule", false, null, null, false, null)));
        Map<String, HealthPortfolioSessions> sessions = store().portfolioSessions(List.of("agent", "none"), LocalDate.of(2026, 10, 31),
                LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 13), List.of("api"), List.of("api", "schedule"));
        assertEquals(Set.of("agent"), sessions.keySet());
        assertEquals(new HealthPortfolioSessions(1, 2, 1, 1), sessions.get("agent"));
    }

    @Test
    @DisplayName("GDPR: by user found newest first and deleted; by conversation deleted")
    default void gdpr() {
        store().upsert(List.of(session("c1", 0, "agent", "alice", T0, "api", true, "FAILED", "structural", false, null),
                session("c1", 9, "agent", "alice", T0.plusSeconds(90_000), "api", true, "FAILED", "structural", false, null),
                session("c2", 0, "agent", "bob", T0, "api", true, "FAILED", "structural", false, null)));
        assertEquals(List.of(9, 0), store().findByUserId("alice", 10).stream().map(HealthOutcome::sessionNo).toList());
        assertEquals(1, store().findByUserId("alice", 1).size());
        assertEquals(2, store().deleteByUserId("alice"));
        assertTrue(store().findByUserId("alice", 10).isEmpty());
        assertEquals(1, store().deleteByConversationId("c2"));
        assertTrue(store().findByConversation("c2").isEmpty());
    }

    @Test
    @DisplayName("retention deletes sessions that ended before the day; the orphan sweep reads recently evaluated conversations")
    default void retentionAndSweep() {
        store().upsert(List.of(closed("old", 0, Instant.parse("2026-08-01T10:00:00Z"), "FAILED", "structural"), closed("new", 0, T0, "FAILED",
                "structural")));
        List<String> evaluated = new ArrayList<>();
        store().forEachConversationEvaluatedSince(T0.minusSeconds(1), 10, evaluated::add);
        assertEquals(Set.of("old", "new"), Set.copyOf(evaluated));
        List<String> none = new ArrayList<>();
        store().forEachConversationEvaluatedSince(T0.plusSeconds(1), 10, none::add);
        assertTrue(none.isEmpty());
        assertEquals(1, store().deleteOlderThan(LocalDate.of(2026, 9, 1)));
        assertTrue(store().findByConversation("old").isEmpty());
        assertEquals(1, store().findByConversation("new").size());
    }

    @Test
    @DisplayName("the watermark is absent until set, then read back")
    default void watermark() {
        assertTrue(store().watermark().isEmpty());
        store().setWatermark(T0);
        assertEquals(T0, store().watermark().orElseThrow());
        store().setWatermark(T0.plusSeconds(5));
        assertEquals(T0.plusSeconds(5), store().watermark().orElseThrow());
    }
}
