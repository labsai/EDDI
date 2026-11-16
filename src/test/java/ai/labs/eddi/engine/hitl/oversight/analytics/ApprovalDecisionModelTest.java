/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.hitl.audit.DecisionOutcome;
import ai.labs.eddi.engine.hitl.audit.EditSummary;
import ai.labs.eddi.engine.hitl.audit.HitlChannel;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit;
import ai.labs.eddi.engine.hitl.audit.HitlDecisionUnit.UnitKind;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalDecision.DeciderKind;
import ai.labs.eddi.engine.hitl.review.ApprovalCategories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.NOW;
import static ai.labs.eddi.engine.hitl.oversight.OversightFixtures.decision;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("approval decision model")
class ApprovalDecisionModelTest {

    private static HitlDecisionUnit unit(String category, String surface, String groupId, String agentId, String toolName, String decidedBy,
                                         boolean automated, HitlChannel channel, String automationReason, UnitKind kind, DecisionOutcome outcome,
                                         ApprovalCategories.Source source, EditSummary edit) {
        return new HitlDecisionUnit("dec-1", 2, surface, "TOOL_CALL", "conv-1", agentId, 3, groupId, "user-1", "production", "p1",
                NOW.minusSeconds(30), NOW, 30_000L, decidedBy, automated, channel, automationReason, "WAIT_INDEFINITELY", kind, "u1",
                outcome, category, source, null, toolName, "abc", edit);
    }

    @Nested
    @DisplayName("DecisionFilter")
    class Filter {

        @Test
        @DisplayName("ofCategory leaves every other field open")
        void ofCategory() {
            var f = DecisionFilter.ofCategory("a", "c", NOW.minusSeconds(100), NOW);
            assertEquals("a", f.agentId());
            assertEquals("c", f.category());
            assertNull(f.groupId());
            assertNull(f.channel());
            assertNull(f.agentVersion());
        }

        @Test
        @DisplayName("an all-null filter passes every row, even one without personal fields")
        void emptyFilterPassesAll() {
            var f = new DecisionFilter(null, null, null, null, null, null, null, null, null);
            assertTrue(f.test(decision("APPROVED", NOW)));
            assertTrue(f.test(decision("APPROVED", NOW).withoutPersonalFields()));
        }

        @Test
        @DisplayName("each field narrows independently")
        void eachFieldNarrows() {
            ApprovalDecision row = decision("APPROVED", NOW);
            assertTrue(new DecisionFilter("agent-1", null, null, null, null, null, null, null, null).test(row));
            assertFalse(new DecisionFilter("other", null, null, null, null, null, null, null, null).test(row));
            assertFalse(new DecisionFilter(null, "grp", null, null, null, null, null, null, null).test(row));
            assertFalse(new DecisionFilter(null, null, "tool:other", null, null, null, null, null, null).test(row));
            assertFalse(new DecisionFilter(null, null, null, null, null, "group", null, null, null).test(row));
            assertTrue(new DecisionFilter(null, null, null, null, null, "regular", null, null, null).test(row));
            assertFalse(new DecisionFilter(null, null, null, null, null, null, "PHASE", null, null).test(row));
            assertTrue(new DecisionFilter(null, null, null, null, null, null, "TOOL_CALL", null, null).test(row));
            assertFalse(new DecisionFilter(null, null, null, null, null, null, null, "SLACK", null).test(row));
            assertTrue(new DecisionFilter(null, null, null, null, null, null, null, "MANAGER", null).test(row));
            assertTrue(new DecisionFilter(null, null, null, null, null, null, null, null, 3).test(row));
            assertFalse(new DecisionFilter(null, null, null, null, null, null, null, null, 4).test(row));
        }

        @Test
        @DisplayName("from is inclusive, to is exclusive, and a row without decidedAt fails a bounded window")
        void windowBounds() {
            ApprovalDecision row = decision("APPROVED", NOW);
            assertTrue(new DecisionFilter(null, null, null, NOW, null, null, null, null, null).test(row));
            assertFalse(new DecisionFilter(null, null, null, NOW.plusSeconds(1), null, null, null, null, null).test(row));
            assertFalse(new DecisionFilter(null, null, null, null, NOW, null, null, null, null).test(row));
            assertTrue(new DecisionFilter(null, null, null, null, NOW.plusSeconds(1), null, null, null, null).test(row));

            ApprovalDecision undated = new ApprovalDecision(row.id(), row.decisionId(), 0, row.surface(), row.pauseType(), row.unitKind(),
                    row.unitId(), row.agentId(), row.agentVersion(), null, null, null, null, row.category(), null, "APPROVED", null, null, null,
                    "alice", DeciderKind.HUMAN_VERIFIED, "MANAGER", null, null, null, false, null, false);
            assertFalse(new DecisionFilter(null, null, null, NOW, null, null, null, null, null).test(undated));
            assertFalse(new DecisionFilter(null, null, null, null, NOW, null, null, null, null).test(undated));
        }
    }

    @Nested
    @DisplayName("DeciderKind")
    class Decider {

        @ParameterizedTest
        @CsvSource({"alice,false,HUMAN_VERIFIED", "system:timeout,false,SYSTEM", "alice,true,SYSTEM", "unknown,false,UNVERIFIED",
                "anonymous,false,UNVERIFIED", "unverified:x,false,UNVERIFIED"})
        void kindOf(String by, boolean automated, DeciderKind expected) {
            assertEquals(expected, DeciderKind.of(by, automated));
        }

        @Test
        @DisplayName("a null or blank decider is unverified unless automated")
        void nullDecider() {
            assertEquals(DeciderKind.UNVERIFIED, DeciderKind.of(null, false));
            assertEquals(DeciderKind.UNVERIFIED, DeciderKind.of("   ", false));
            assertEquals(DeciderKind.SYSTEM, DeciderKind.of(null, true));
        }
    }

    @Nested
    @DisplayName("of(unit)")
    class OfUnit {

        @Test
        @DisplayName("a categorised unit keeps its category, outcome, edit figures and declared channel")
        void categorised() {
            var edit = new EditSummary(EditSummary.Kind.TOOL_ARGUMENTS, 10, 12, 4, false, 0.4, "a", "b", 1, null);
            ApprovalDecision d = ApprovalDecision.of(unit("tool:x", "regular", null, "agent-1", "createNote", "alice", false, HitlChannel.SLACK,
                    null, UnitKind.TOOL_CALL, DecisionOutcome.APPROVED_EDITED, ApprovalCategories.Source.CONFIG, edit), 4);

            assertEquals("dec-1#4", d.id());
            assertEquals("tool:x", d.category());
            assertFalse(d.legacy());
            assertEquals("CONFIG", d.categorySource());
            assertEquals("APPROVED_EDITED", d.outcome());
            assertEquals("TOOL_CALL", d.unitKind());
            assertEquals(4, d.editChars());
            assertEquals(0.4, d.editRatio());
            assertEquals("SLACK", d.channel());
            assertEquals(DeciderKind.HUMAN_VERIFIED, d.deciderKind());
            assertFalse(d.timedOut());
        }

        @Test
        @DisplayName("a unit without optional parts leaves those fields null, and the channel comes from decidedBy")
        void sparse() {
            ApprovalDecision d = ApprovalDecision.of(unit("tool:x", "regular", null, "a", null, "system:timeout", true, null, null, null, null,
                    null, null), 0);
            assertNull(d.unitKind());
            assertNull(d.categorySource());
            assertNull(d.outcome());
            assertNull(d.editChars());
            assertNull(d.editRatio());
            assertEquals("TIMEOUT", d.channel());
            assertTrue(d.timedOut());
            assertEquals(DeciderKind.SYSTEM, d.deciderKind());
            assertFalse(d.humanOutcome());
        }

        @Test
        @DisplayName("TIMEOUT_POLICY as the automation reason marks a timeout even on another channel")
        void timeoutPolicyReason() {
            ApprovalDecision d = ApprovalDecision.of(unit("tool:x", "regular", null, "a", null, "alice", false, HitlChannel.REST, "TIMEOUT_POLICY",
                    UnitKind.TOOL_CALL, DecisionOutcome.AUTO_APPROVED, null, null), 0);
            assertTrue(d.timedOut());
        }

        @Test
        @DisplayName("legacy units get a legacy: category by tool, group, agent, or unknown")
        void legacyCategories() {
            assertEquals("legacy:tool:createNote", ApprovalDecision.of(unit(null, "regular", null, "a", "createNote", "alice", false, null, null,
                    null, null, null, null), 0).category());
            assertEquals("legacy:group:g1", ApprovalDecision.of(unit(null, "group", "g1", "a", null, "alice", false, null, null, null, null,
                    null, null), 0).category());
            assertEquals("legacy:group:g1", ApprovalDecision.of(unit(null, "GROUP", "g1", "a", null, "alice", false, null, null, null, null,
                    null, null), 0).category());
            assertEquals("legacy:rule:a", ApprovalDecision.of(unit(null, "group", null, "a", null, "alice", false, null, null, null, null,
                    null, null), 0).category());
            assertEquals("legacy:rule:a", ApprovalDecision.of(unit(null, null, "g1", "a", null, "alice", false, null, null, null, null, null,
                    null), 0).category());
            ApprovalDecision unknown = ApprovalDecision.of(unit(null, null, null, null, null, "alice", false, null, null, null, null, null,
                    null), 0);
            assertEquals("legacy:unknown", unknown.category());
            assertTrue(unknown.legacy());
        }

        @Test
        @DisplayName("humanOutcome is true for approve, edited approve and reject only")
        void humanOutcome() {
            assertTrue(decision("APPROVED", NOW).humanOutcome());
            assertTrue(decision("APPROVED_EDITED", NOW).humanOutcome());
            assertTrue(decision("REJECTED", NOW).humanOutcome());
            assertFalse(decision("AUTO_APPROVED", NOW).humanOutcome());
            assertFalse(decision(null, NOW).humanOutcome());
        }

        @Test
        @DisplayName("personal fields can be stripped and replaced")
        void personalFields() {
            ApprovalDecision d = decision("APPROVED", NOW);
            ApprovalDecision stripped = d.withoutPersonalFields();
            assertNull(stripped.userId());
            assertNull(stripped.decidedBy());
            ApprovalDecision replaced = d.withPersonalFields("u2", "pseudo");
            assertEquals("u2", replaced.userId());
            assertEquals("pseudo", replaced.decidedBy());
            assertEquals(d.id(), replaced.id());
            assertEquals(Instant.parse("2026-11-02T12:00:00Z"), replaced.decidedAt());
        }
    }
}