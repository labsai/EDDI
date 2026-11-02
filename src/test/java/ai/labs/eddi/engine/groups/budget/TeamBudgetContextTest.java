/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.groups.budget;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig.Oversight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TeamBudgetContextTest {

    private static final String MONTH = "2026-11";

    private TeamBudgetTestSupport t;
    private TeamBudgetContext context;

    @BeforeEach
    void setUp() {
        t = new TeamBudgetTestSupport();
        context = new TeamBudgetContext(t.service);
        AgentGroupConfiguration board = t.group("board", "Growth Board", 10.0, CostPolicy.SYNTHESIZE_NOW);
        board.setTeamBudget(new TeamBudgetConfig(10.0, null, new Oversight(List.of("studio", "radar", "hidden", "gone"))));
        t.group("studio", "Content Studio", 35.0, null);
        t.group("radar", "Signal Radar", 30.0, null);
        t.group("hidden", "Hidden", 5.0, null);
        t.store.addSpend("studio", MONTH, 12.5, TeamBudgetTestSupport.CLOCK.instant());
        t.store.addSpend("hidden", MONTH, 4.0, TeamBudgetTestSupport.CLOCK.instant());
    }

    private static GroupConversation gc(String groupId) {
        GroupConversation gc = new GroupConversation();
        gc.setId("gc-" + groupId);
        gc.setGroupId(groupId);
        return gc;
    }

    @SuppressWarnings("unchecked")
    @Test
    void aBoardSeesOnlyTheTeamsItsOwnerMayView() {
        Map<String, Object> values = context.values(gc("board"), Set.of("studio", "radar")::contains);

        Map<String, Object> own = (Map<String, Object>) values.get("teamBudget");
        assertEquals(10.0, own.get("capUsd"));
        List<Map<String, Object>> board = (List<Map<String, Object>>) values.get("teamBudgets");
        assertEquals(List.of("studio", "radar"), board.stream().map(m -> m.get("groupId")).toList(),
                "'hidden' is not viewable and 'gone' does not exist: both dropped");
        assertEquals(12.5, board.get(0).get("spentUsd"));
        assertEquals(22.5, board.get(0).get("remainingUsd"));
    }

    @Test
    void noActorsReasonsOrTranscriptsLeakIntoTheValues() {
        t.service.setCurrentMonthCap("studio", 40.0, "secret reason", "alice");
        Map<String, Object> values = context.values(gc("board"), id -> true);
        String dump = values.toString();
        assertFalse(dump.contains("secret reason"), dump);
        assertFalse(dump.contains("alice"), dump);
        assertFalse(dump.contains("adjustments"), dump);
    }

    @Test
    void aGroupWithoutATeamBudgetGetsNoValues() {
        assertTrue(context.values(gc("free"), id -> true).isEmpty());
        t.group("free", "Free", null, null);
        assertTrue(context.values(gc("free"), id -> true).isEmpty());
    }

    @Test
    void aGroupWithoutOversightGetsOnlyItsOwnBudget() {
        Map<String, Object> values = context.values(gc("studio"), id -> true);
        assertTrue(values.containsKey("teamBudget"));
        assertFalse(values.containsKey("teamBudgets"));
    }

    @Test
    void anOutageYieldsNoValuesNotZeros() {
        t.store.down = true;
        assertTrue(context.values(gc("board"), id -> true).isEmpty());
    }

    @Test
    void theValuesAreCachedPerDiscussionSoATurnCostsNoExtraIo() {
        GroupConversation gc = gc("board");
        context.values(gc, id -> true);
        int calls = t.store.calls.get();
        context.values(gc, id -> true);
        assertEquals(calls, t.store.calls.get());
    }
}
