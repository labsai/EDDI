/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.model;

import ai.labs.eddi.configs.groups.model.SharedTaskList.Bet;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Bets on shared tasks")
class SharedTaskListBetTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    private static Bet bet(String reviewDate) {
        return Bet.of("a founder post gets replies", "replies_received", ">= 5", reviewDate);
    }

    @Nested
    @DisplayName("Bet.of")
    class Validation {

        @Test
        void allBlankIsNotABet() {
            assertNull(Bet.of(null, "", "  ", null));
        }

        @Test
        void hypothesisMetricAndReviewDateAreRequiredTogether() {
            assertThrows(IllegalArgumentException.class, () -> Bet.of("h", null, null, "2026-12-01"));
            assertThrows(IllegalArgumentException.class, () -> Bet.of(null, "m", null, "2026-12-01"));
            assertThrows(IllegalArgumentException.class, () -> Bet.of("h", "m", "t", null));
            assertNull(Bet.of("h", "m", null, "2026-12-01").target(), "target is optional");
        }

        @Test
        void aDateMeansUtcMidnightAndAnInstantIsKept() {
            assertEquals(Instant.parse("2026-12-01T00:00:00Z"), bet("2026-12-01").reviewDate());
            assertEquals(Instant.parse("2026-12-01T09:30:00Z"), bet("2026-12-01T09:30:00Z").reviewDate());
        }

        @Test
        void aMalformedDateIsRefusedWithAnActionableMessage() {
            var e = assertThrows(IllegalArgumentException.class, () -> bet("next tuesday"));
            assertTrue(e.getMessage().contains("ISO"), e.getMessage());
            assertThrows(IllegalArgumentException.class, () -> Bet.parseReviewDate(null));
        }

        @Test
        void everyFieldIsBounded() {
            String tooLong = "x".repeat(Bet.MAX_FIELD_CHARS + 1);
            assertThrows(IllegalArgumentException.class, () -> Bet.of(tooLong, "m", null, "2026-12-01"));
            assertThrows(IllegalArgumentException.class, () -> Bet.of("h", tooLong, null, "2026-12-01"));
            assertThrows(IllegalArgumentException.class, () -> Bet.of("h", "m", tooLong, "2026-12-01"));
            assertNotNull(Bet.of("x".repeat(Bet.MAX_FIELD_CHARS), "m", null, "2026-12-01"));
        }
    }

    @Nested
    @DisplayName("due reviews")
    class DueReviews {

        @Test
        void aBetIsDueFromItsReviewDateUntilItIsReviewed() {
            Bet b = bet("2026-11-03");
            assertFalse(b.dueAt(NOW.minusSeconds(13 * 3600)), "the day before");
            assertTrue(b.dueAt(Instant.parse("2026-11-03T00:00:00Z")), "on the review instant");
            assertTrue(b.dueAt(NOW.plusSeconds(86400 * 30)));
            assertFalse(b.reviewed(NOW).dueAt(NOW.plusSeconds(86400 * 30)), "a verdict closes it");
            assertTrue(b.reviewed(NOW).rescheduled(Instant.parse("2026-12-01T00:00:00Z")).reviewedAt() == null, "rescheduling reopens");
        }

        @Test
        void findDueReviewsSkipsOrdinaryTasksFutureAndReviewedBetsAndSortsByDate() {
            var list = new SharedTaskList();
            list.addTask(new TaskItem("ordinary", "d", 0));
            list.addTask(new TaskItem("later", "d", 0, bet("2026-11-10")));
            list.addTask(new TaskItem("b-due", "d", 0, bet("2026-11-02")));
            list.addTask(new TaskItem("a-due-earlier", "d", 0, bet("2026-10-20")));
            TaskItem reviewed = list.addTask(new TaskItem("done", "d", 0, bet("2026-10-01").reviewed(NOW)));
            assertNotNull(reviewed);

            assertEquals(List.of("a-due-earlier", "b-due"), list.findDueReviews(NOW).stream().map(TaskItem::subject).toList());
            assertEquals(3, list.findDueReviews(Instant.parse("2026-11-11T00:00:00Z")).size(), "asOf asks what will be due by then");
        }

        @Test
        void aVerifiedTaskStillCountsBecauseTheBetIsJudgedByWhatItMeasured() {
            var list = new SharedTaskList();
            TaskItem t = list.addTask(new TaskItem("shipped", "d", 0, bet("2026-10-01")));
            list.assignTask(t.id(), "agent", "Agent");
            list.startTask(t.id());
            list.completeTask(t.id(), "done");
            list.verifyTask(t.id(), true, "ok");

            assertEquals(TaskStatus.VERIFIED, list.findById(t.id()).status());
            assertEquals(1, list.findDueReviews(NOW).size());
        }
    }

    @Nested
    @DisplayName("the bet survives every transition")
    class Preservation {

        private final Bet expected = bet("2026-12-01");

        private SharedTaskList list;
        private String id;

        private void fresh() {
            list = new SharedTaskList();
            id = list.addTask(new TaskItem("bet", "d", 0, expected)).id();
        }

        private void assertKept(String step) {
            assertEquals(expected, list.findById(id).bet(), "bet lost by " + step);
        }

        @Test
        void happyPath() {
            fresh();
            list.assignTask(id, "agent", "Agent");
            assertKept("assignTask");
            list.startTask(id);
            assertKept("startTask");
            list.completeTask(id, "r");
            assertKept("completeTask");
            list.verifyTask(id, true, "ok");
            assertKept("verifyTask");
        }

        @Test
        void approvalPath() {
            fresh();
            list.assignTask(id, "agent", "Agent");
            list.startTask(id);
            list.submitForApproval(id, "r");
            assertKept("submitForApproval");
            list.approveTask(id);
            assertKept("approveTask");
        }

        @Test
        void rejectionAndRework() {
            fresh();
            list.assignTask(id, "agent", "Agent");
            list.startTask(id);
            list.submitForApproval(id, "r");
            list.rejectTask(id, "no");
            assertKept("rejectTask");
            list.reworkTask(id, "again");
            assertKept("reworkTask / resetFromAnyToAssigned");
        }

        @Test
        void failureAndReset() {
            fresh();
            list.assignTask(id, "agent", "Agent");
            list.startTask(id);
            list.failTask(id, "boom");
            assertKept("failTask");
            list.resetFromAnyToAssigned(id, "retry");
            assertKept("resetFromAnyToAssigned");
        }

        @Test
        void editedApproval() {
            fresh();
            list.assignTask(id, "agent", "Agent");
            list.startTask(id);
            list.submitForApproval(id, "r");
            list.approveTaskWithEditedResult(id, "edited", null);
            assertKept("approveTaskWithEditedResult");
        }

        @Test
        void filedByAnAgentAsABet() {
            var l = new SharedTaskList();
            var result = l.addAgentTask("agent bet", "d", null, 0, "agent-1", "agent-1", "Agent One", 0, expected);
            assertTrue(result.accepted());
            assertEquals(expected, result.task().bet());
            assertNull(l.addAgentTask("plain", "d", null, 0, "agent-1", "agent-1", "Agent One", 0).task().bet());
        }
    }

    @Test
    @DisplayName("a task document written before bets existed reads unchanged, and a bet round-trips through JSON")
    void jsonCompatibility() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String old = """
                {"id":"t1","subject":"s","description":"d","status":"PENDING","assignedAgentId":null,"assignedDisplayName":null,
                 "dependsOnIds":[],"result":null,"verificationNote":null,"verified":false,"priority":1,
                 "createdAt":"2026-11-01T10:00:00Z","completedAt":null,"createdByAgentId":null}
                """;
        TaskItem read = mapper.readValue(old, TaskItem.class);
        assertNull(read.bet());

        TaskItem withBet = new TaskItem("s", "d", 1, bet("2026-12-01"));
        TaskItem back = mapper.readValue(mapper.writeValueAsString(withBet), TaskItem.class);
        assertEquals(withBet.bet(), back.bet());
    }
}
