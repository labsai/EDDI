/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.AssignmentMode;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupTaskConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TaskDefinition;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I18 — {@link TaskBidEngine}: the three-tier bid parse, deterministic awarding
 * (highest confidence, speaking-order tie-break), the effective-mode resolution
 * chain, the worthwhile-auction caps, and the blind prompt. Pure unit tests, no
 * mocks — the engine is deliberately static.
 *
 * @author tests
 */
class TaskBidEngineTest {

    private static final GroupMember ALICE = new GroupMember("a1", "Alice", 1, null);
    private static final GroupMember BOB = new GroupMember("a2", "Bob", 2, null);

    private static final Set<String> SUBJECTS = Set.of("Write the migration", "Update the docs");

    private TaskItem task(String subject) {
        return new TaskItem(subject, "desc of " + subject, 0);
    }

    // =================================================================
    // parsing
    // =================================================================

    @Test
    @DisplayName("strict and fenced JSON parse; prose casts no bids; confidence is clamped to [0,1]")
    void parse_tiers_andClamping() {
        var strict = TaskBidEngine.parseBids(
                "{\"bids\": [{\"subject\": \"Write the migration\", \"confidence\": 1.7, \"estimatedComplexity\": \"M\", \"rationale\": \"I own the schema\"}]}",
                ALICE, SUBJECTS);
        assertEquals(1, strict.size());
        assertEquals(1.0, strict.get(0).confidence(), "confidence clamps at 1.0");

        var fenced = TaskBidEngine.parseBids(
                "My bids:\n```json\n{\"bids\": [{\"subject\": \"Update the docs\", \"confidence\": -0.5}]}\n```",
                ALICE, SUBJECTS);
        assertEquals(1, fenced.size());
        assertEquals(0.0, fenced.get(0).confidence(), "confidence clamps at 0.0");

        assertTrue(TaskBidEngine.parseBids("I'd love to take the migration work!", ALICE, SUBJECTS).isEmpty(),
                "prose is never guessed into a bid");
        assertTrue(TaskBidEngine.parseBids(null, ALICE, SUBJECTS).isEmpty());
    }

    @Test
    @DisplayName("bids on unannounced tasks are dropped; duplicate subjects keep the first")
    void parse_unknownSubjectsDropped() {
        var bids = TaskBidEngine.parseBids(
                "{\"bids\": [{\"subject\": \"Something else entirely\", \"confidence\": 0.9}, "
                        + "{\"subject\": \"write the migration\", \"confidence\": 0.6}, "
                        + "{\"subject\": \"Write the Migration\", \"confidence\": 0.99}]}",
                ALICE, SUBJECTS);

        assertEquals(1, bids.size(), "unknown dropped; case-insensitive canonical match; first-per-task wins");
        assertEquals("Write the migration", bids.get(0).taskSubject());
        assertEquals(0.6, bids.get(0).confidence());
    }

    // =================================================================
    // awarding
    // =================================================================

    @Test
    @DisplayName("each task goes to the highest confidence")
    void award_highestConfidenceWins() {
        var t1 = task("Write the migration");
        var t2 = task("Update the docs");
        var bids = List.of(
                new TaskBidEngine.Bid("a1", 1, "Write the migration", 0.9, "M", "schema owner"),
                new TaskBidEngine.Bid("a2", 2, "Write the migration", 0.4, "L", null),
                new TaskBidEngine.Bid("a2", 2, "Update the docs", 0.8, "S", "wrote v1 docs"));

        var awards = TaskBidEngine.award(List.of(t1, t2), bids);

        assertEquals("a1", awards.get(t1.id()).agentId());
        assertEquals(0.9, awards.get(t1.id()).confidence());
        assertEquals("schema owner", awards.get(t1.id()).rationale());
        assertEquals("a2", awards.get(t2.id()).agentId());
    }

    @Test
    @DisplayName("a tie breaks deterministically: lowest speaking order, then agent id")
    void award_tieBreakDeterminism() {
        var t1 = task("Write the migration");
        var byOrder = TaskBidEngine.award(List.of(t1), List.of(
                new TaskBidEngine.Bid("a2", 2, "Write the migration", 0.7, null, null),
                new TaskBidEngine.Bid("a1", 1, "Write the migration", 0.7, null, null)));
        assertEquals("a1", byOrder.get(t1.id()).agentId(), "lowest speakingOrder wins a tie");

        var byId = TaskBidEngine.award(List.of(t1), List.of(
                new TaskBidEngine.Bid("z-agent", null, "Write the migration", 0.7, null, null),
                new TaskBidEngine.Bid("b-agent", null, "Write the migration", 0.7, null, null)));
        assertEquals("b-agent", byId.get(t1.id()).agentId(), "unordered members resolve by agent id — same on every pod");
    }

    @Test
    @DisplayName("a task nobody bid on is absent from the awards — the caller falls back to ROLE")
    void award_noBids_absent() {
        var t1 = task("Write the migration");

        assertTrue(TaskBidEngine.award(List.of(t1), List.of()).isEmpty());
    }

    // =================================================================
    // effective mode + caps
    // =================================================================

    @Test
    @DisplayName("mode resolution: task's own mode → group default → ROLE")
    void effectiveMode_chain() {
        var config = new AgentGroupConfiguration();
        assertEquals(AssignmentMode.ROLE, TaskBidEngine.effectiveMode(null, config), "pre-I18 configs resolve to ROLE");

        config.setTaskListConfig(new GroupTaskConfig(false, 20, 3, AssignmentMode.BID));
        assertEquals(AssignmentMode.BID, TaskBidEngine.effectiveMode(null, config), "group default applies");

        var roleTask = new TaskDefinition("t", "d", "ALL", List.of(), 0, AssignmentMode.ROLE);
        assertEquals(AssignmentMode.ROLE, TaskBidEngine.effectiveMode(roleTask, config), "the task's own mode wins");

        var bidTask = new TaskDefinition("t", "d", "ALL", List.of(), 0, AssignmentMode.BID);
        assertEquals(AssignmentMode.BID, TaskBidEngine.effectiveMode(bidTask, new AgentGroupConfiguration()));
    }

    @Test
    @DisplayName("the auction only runs when it can beat its own overhead: ≥2 bidders AND ≥2 tasks")
    void auctionWorthwhile_caps() {
        assertTrue(TaskBidEngine.auctionWorthwhile(2, 2));
        assertFalse(TaskBidEngine.auctionWorthwhile(1, 5), "one bidder — the winner is predetermined");
        assertFalse(TaskBidEngine.auctionWorthwhile(5, 1), "one task — a round-robin assignment costs zero LLM calls");
        assertFalse(TaskBidEngine.auctionWorthwhile(0, 0));
    }

    // =================================================================
    // the blind prompt
    // =================================================================

    @Test
    @DisplayName("the bid prompt announces the tasks and the contract — and nothing else")
    void buildBidPrompt_blind() {
        var prompt = TaskBidEngine.buildBidPrompt(List.of(task("Write the migration"), task("Update the docs")),
                BOB, "Ship the release");

        assertTrue(prompt.contains("Write the migration"), prompt);
        assertTrue(prompt.contains("Update the docs"), prompt);
        assertTrue(prompt.contains("\"bids\""), "the JSON contract is in the prompt");
        assertTrue(prompt.contains("Bob"));
        // Blindness: the prompt is built from the announced tasks alone — there
        // is no transcript parameter to leak peer bids through, and the honesty
        // rule is stated to the model.
        assertTrue(prompt.contains("inflated confidence"), prompt);
    }
}
