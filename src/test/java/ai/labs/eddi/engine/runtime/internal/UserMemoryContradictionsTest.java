/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry.Assessment;
import ai.labs.eddi.engine.runtime.internal.UserMemoryContradictions.Change;
import ai.labs.eddi.engine.runtime.internal.UserMemoryContradictions.Plan;
import ai.labs.eddi.engine.runtime.internal.UserMemoryContradictions.Resolution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("UserMemoryContradictions — the contradiction plan")
class UserMemoryContradictionsTest {

    private static final String USER = "user-1";
    private static final String AGENT_A = "agent-a";
    private static final String AGENT_B = "agent-b";
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private static UserMemoryEntry entry(String id, String key, Object value, Visibility visibility, String agent, List<String> groups,
                                         Instant updatedAt, Assessment assessment, String supersededBy) {
        return new UserMemoryEntry(id, USER, key, value, "fact", visibility, agent, groups, null, assessment == Assessment.CONTESTED, 0, T0,
                updatedAt, assessment, supersededBy);
    }

    private static UserMemoryEntry self(String id, String agent, Object value, Instant updatedAt) {
        return entry(id, "language", value, Visibility.self, agent, List.of(), updatedAt, Assessment.ACCEPTED, null);
    }

    private static UserMemoryEntry global(String id, String agent, Object value, Instant updatedAt) {
        return entry(id, "language", value, Visibility.global, agent, List.of(), updatedAt, Assessment.ACCEPTED, null);
    }

    private static Set<String> ids(UserMemoryEntry... entries) {
        return Arrays.stream(entries).map(UserMemoryEntry::id).collect(Collectors.toSet());
    }

    /**
     * id → assessment[/supersededBy] of every change, sorted for stable asserts.
     */
    private static Map<String, String> changes(Plan plan) {
        Map<String, String> out = new TreeMap<>();
        for (Change change : plan.changes()) {
            out.put(change.entry().id(), change.assessment() + (change.supersededBy() != null ? "/" + change.supersededBy() : ""));
        }
        return out;
    }

    @Nested
    @DisplayName("Resolution.fromConfig")
    class FromConfig {

        @Test
        @DisplayName("null and blank mean the default")
        void nullAndBlankAreDefault() {
            assertEquals(Resolution.KEEP_NEWEST, Resolution.fromConfig(null));
            assertEquals(Resolution.KEEP_NEWEST, Resolution.fromConfig("  "));
        }

        @Test
        @DisplayName("each documented value, any case and padding")
        void documentedValues() {
            assertEquals(Resolution.KEEP_NEWEST, Resolution.fromConfig("keep_newest"));
            assertEquals(Resolution.MARK_CONTESTED, Resolution.fromConfig(" Mark_Contested "));
            assertEquals(Resolution.SUPERSEDE_OLDER, Resolution.fromConfig("SUPERSEDE_OLDER"));
        }

        @Test
        @DisplayName("an unknown value names no strategy")
        void unknownIsNull() {
            assertNull(Resolution.fromConfig("keep_oldest"));
        }
    }

    @Nested
    @DisplayName("detection")
    class Detection {

        @ParameterizedTest
        @EnumSource(Resolution.class)
        @DisplayName("counts adjacent disagreeing pairs exactly as before, whatever the strategy")
        void countIsUnchanged(Resolution resolution) {
            var a = self("a", AGENT_A, "English", T0);
            var b = self("b", AGENT_B, "German", T0.plusSeconds(10));
            var c = global("c", AGENT_B, "German", T0.plusSeconds(20));

            Plan plan = UserMemoryContradictions.plan(List.of(a, b, c), ids(a), AGENT_A, false, resolution);

            assertEquals(1, plan.contradictions().size());
            assertEquals("a", plan.contradictions().getFirst().previous().id());
            assertEquals("b", plan.contradictions().getFirst().entry().id());
        }

        @Test
        @DisplayName("a disagreement between two other agents is not this agent's to report")
        void foreignDisagreementIgnored() {
            var b = self("b", AGENT_B, "German", T0);
            var c = self("c", "agent-c", "French", T0);

            Plan plan = UserMemoryContradictions.plan(List.of(b, c), Set.of(), AGENT_A, false, Resolution.MARK_CONTESTED);

            assertTrue(plan.contradictions().isEmpty());
            assertTrue(plan.changes().isEmpty());
        }

        @Test
        @DisplayName("a superseded entry no longer contradicts anything")
        void supersededIsNotCounted() {
            var newer = global("g", AGENT_B, "German", T0.plusSeconds(10));
            var older = entry("a", "language", "English", Visibility.self, AGENT_A, List.of(), T0, Assessment.SUPERSEDED, "g");

            Plan plan = UserMemoryContradictions.plan(List.of(older, newer), ids(older), AGENT_A, false, Resolution.SUPERSEDE_OLDER);

            assertTrue(plan.contradictions().isEmpty());
            assertTrue(plan.changes().isEmpty());
        }
    }

    @Nested
    @DisplayName("keep_newest")
    class KeepNewest {

        @Test
        @DisplayName("changes nothing — today's log-only behaviour")
        void changesNothing() {
            var a = self("a", AGENT_A, "English", T0);
            var g = global("g", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, g), ids(a, g), AGENT_A, true, Resolution.KEEP_NEWEST);

            assertEquals(1, plan.contradictions().size());
            assertTrue(plan.changes().isEmpty());
        }

        @Test
        @DisplayName("still lifts a contest that no longer exists, so switching back strands no flag")
        void liftsStaleContest() {
            var a = entry("a", "language", "German", Visibility.self, AGENT_A, List.of(), T0, Assessment.CONTESTED, null);
            var g = global("g", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, g), ids(a), AGENT_A, false, Resolution.KEEP_NEWEST);

            assertEquals(Map.of("a", "ACCEPTED"), changes(plan));
        }
    }

    @Nested
    @DisplayName("mark_contested")
    class MarkContested {

        @Test
        @DisplayName("flags every writable entry of the disputed key and keeps them all")
        void flagsBoth() {
            var a = self("a", AGENT_A, "English", T0);
            var g = global("g", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, g), ids(a, g), AGENT_A, true, Resolution.MARK_CONTESTED);

            assertEquals(Map.of("a", "CONTESTED", "g", "CONTESTED"), changes(plan));
        }

        @Test
        @DisplayName("without crossAgentMaintenance only this agent's own entry is flagged")
        void ownEntryOnly() {
            var a = self("a", AGENT_A, "English", T0);
            var b = self("b", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, b), ids(a), AGENT_A, false, Resolution.MARK_CONTESTED);

            assertEquals(Map.of("a", "CONTESTED"), changes(plan));
        }

        @Test
        @DisplayName("an entry already contested is not written again")
        void idempotent() {
            var a = entry("a", "language", "English", Visibility.self, AGENT_A, List.of(), T0, Assessment.CONTESTED, null);
            var b = self("b", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, b), ids(a), AGENT_A, false, Resolution.MARK_CONTESTED);

            assertTrue(plan.changes().isEmpty());
        }

        @Test
        @DisplayName("agreeing entries are not a contest")
        void agreementIsNoContest() {
            var a = self("a", AGENT_A, "German", T0);
            var b = self("b", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, b), ids(a, b), AGENT_A, true, Resolution.MARK_CONTESTED);

            assertTrue(plan.contradictions().isEmpty());
            assertTrue(plan.changes().isEmpty());
        }

        @Test
        @DisplayName("an entry whose rival was deleted is no longer contested")
        void lonelyContestLifted() {
            var a = entry("a", "language", "English", Visibility.self, AGENT_A, List.of(), T0, Assessment.CONTESTED, null);

            Plan plan = UserMemoryContradictions.plan(List.of(a), ids(a), AGENT_A, false, Resolution.MARK_CONTESTED);

            assertEquals(Map.of("a", "ACCEPTED"), changes(plan));
        }

        @Test
        @DisplayName("an entry without an id cannot be written and is left alone")
        void noIdNoChange() {
            var a = self(null, AGENT_A, "English", T0);
            var b = self("b", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, b), ids(b), AGENT_A, true, Resolution.MARK_CONTESTED);

            assertEquals(Map.of("b", "CONTESTED"), changes(plan));
        }
    }

    @Nested
    @DisplayName("supersede_older")
    class SupersedeOlder {

        @Test
        @DisplayName("a newer global entry supersedes an older self entry; nothing is deleted")
        void globalSupersedesSelf() {
            var a = self("a", AGENT_A, "English", T0);
            var g = global("g", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, g), ids(a), AGENT_A, false, Resolution.SUPERSEDE_OLDER);

            assertEquals(Map.of("a", "SUPERSEDED/g"), changes(plan));
        }

        @Test
        @DisplayName("a newer self entry of another agent cannot reach this agent's readers — both stay, contested")
        void selfCannotSupersede() {
            var a = self("a", AGENT_A, "English", T0);
            var b = self("b", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, b), ids(a, b), AGENT_A, true, Resolution.SUPERSEDE_OLDER);

            assertEquals(Map.of("a", "CONTESTED", "b", "CONTESTED"), changes(plan));
        }

        @Test
        @DisplayName("the newest entry is never superseded, even when it is the one this agent owns")
        void newestStays() {
            var g = global("g", AGENT_B, "English", T0);
            var a = self("a", AGENT_A, "German", T0.plusSeconds(10));

            // a is newer but self-scoped, so it cannot replace g for g's readers
            Plan plan = UserMemoryContradictions.plan(List.of(g, a), ids(a), AGENT_A, false, Resolution.SUPERSEDE_OLDER);

            assertEquals(Map.of("a", "CONTESTED"), changes(plan));
        }

        @Test
        @DisplayName("a group entry covering the older entry's groups supersedes it")
        void groupCoversGroup() {
            var older = entry("o", "language", "English", Visibility.group, AGENT_A, List.of("g1"), T0, Assessment.ACCEPTED, null);
            var newer = entry("n", "language", "German", Visibility.group, AGENT_B, List.of("g1", "g2"), T0.plusSeconds(5), Assessment.ACCEPTED,
                    null);

            Plan plan = UserMemoryContradictions.plan(List.of(older, newer), ids(older, newer), AGENT_A, true, Resolution.SUPERSEDE_OLDER);

            assertEquals(Map.of("o", "SUPERSEDED/n"), changes(plan));
        }

        @Test
        @DisplayName("a newest timestamp shared by disagreeing entries picks no winner")
        void tieIsContested() {
            var a = global("g", AGENT_B, "German", T0);
            var b = self("a", AGENT_A, "English", T0);

            Plan plan = UserMemoryContradictions.plan(List.of(a, b), ids(a, b), AGENT_A, true, Resolution.SUPERSEDE_OLDER);

            assertEquals(Map.of("a", "CONTESTED", "g", "CONTESTED"), changes(plan));
        }

        @Test
        @DisplayName("an older entry outside the writable set is left alone, and the conflict stays visible on the rest")
        void foreignOlderUntouched() {
            var b = self("b", AGENT_B, "English", T0);
            var a = self("a", AGENT_A, "French", T0.plusSeconds(5));
            var g = global("g", "agent-c", "German", T0.plusSeconds(10));

            // b could be superseded by g, but it is not this agent's
            Plan plan = UserMemoryContradictions.plan(List.of(b, a, g), ids(a), AGENT_A, false, Resolution.SUPERSEDE_OLDER);

            assertEquals(Map.of("a", "SUPERSEDED/g"), changes(plan));
        }

        @Test
        @DisplayName("a superseded entry whose replacement is gone is accepted again")
        void orphanRevived() {
            var a = entry("a", "language", "English", Visibility.self, AGENT_A, List.of(), T0, Assessment.SUPERSEDED, "gone");

            Plan plan = UserMemoryContradictions.plan(List.of(a), ids(a), AGENT_A, false, Resolution.SUPERSEDE_OLDER);

            assertEquals(Map.of("a", "ACCEPTED"), changes(plan));
        }

        @Test
        @DisplayName("a chain collapses onto its newest link")
        void chainCollapses() {
            var a = entry("a", "language", "English", Visibility.self, AGENT_A, List.of(), T0, Assessment.SUPERSEDED, "o");
            var o = entry("o", "language", "French", Visibility.group, AGENT_A, List.of("g1"), T0.plusSeconds(5), Assessment.SUPERSEDED, "g");
            var g = global("g", AGENT_B, "German", T0.plusSeconds(10));

            Plan plan = UserMemoryContradictions.plan(List.of(a, o, g), ids(a, o), AGENT_A, false, Resolution.SUPERSEDE_OLDER);

            assertEquals(Map.of("a", "SUPERSEDED/g"), changes(plan));
        }
    }

    @Nested
    @DisplayName("reachesEveryReaderOf")
    class Coverage {

        @Test
        @DisplayName("global reaches everyone, self reaches no one else, group only a narrower group")
        void coverage() {
            var selfA = self("a", AGENT_A, "x", T0);
            var globalB = global("g", AGENT_B, "y", T0);
            var groupNarrow = entry("n", "language", "z", Visibility.group, AGENT_B, List.of("g1"), T0, Assessment.ACCEPTED, null);
            var groupWide = entry("w", "language", "z", Visibility.group, AGENT_B, List.of("g1", "g2"), T0, Assessment.ACCEPTED, null);
            var groupNone = entry("e", "language", "z", Visibility.group, AGENT_B, List.of(), T0, Assessment.ACCEPTED, null);

            assertTrue(UserMemoryContradictions.reachesEveryReaderOf(globalB, selfA));
            assertFalse(UserMemoryContradictions.reachesEveryReaderOf(selfA, globalB));
            assertFalse(UserMemoryContradictions.reachesEveryReaderOf(groupWide, selfA));
            assertTrue(UserMemoryContradictions.reachesEveryReaderOf(groupWide, groupNarrow));
            assertFalse(UserMemoryContradictions.reachesEveryReaderOf(groupNarrow, groupWide));
            assertFalse(UserMemoryContradictions.reachesEveryReaderOf(groupWide, groupNone));
            assertFalse(UserMemoryContradictions.reachesEveryReaderOf(groupWide, globalB));
        }
    }
}
