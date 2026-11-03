/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FollowUpToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String SOON = "2026-11-10T09:00:00Z";

    private IScheduleStore store;
    private final List<ScheduleConfiguration> stored = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        store = mock(IScheduleStore.class);
        when(store.createSchedule(any())).thenAnswer(inv -> {
            ScheduleConfiguration schedule = inv.getArgument(0);
            schedule.setId("sched-" + (stored.size() + 1));
            stored.add(schedule);
            return schedule.getId();
        });
        when(store.readSchedulesByAgentId(anyString(), anyInt(), anyInt(), anyBoolean())).thenAnswer(inv -> List.copyOf(stored));
    }

    private FollowUpTools tools(AgentOpsConfig.FollowUp limits, boolean inGroup) {
        return new FollowUpTools(new FollowUpTools.Booker("agent-1", "alice", "conv-1", inGroup, List.of("team-a")), limits, store, CLOCK);
    }

    private FollowUpTools tools() {
        return tools(null, false);
    }

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    @Test
    void booksAOneShotForTheOwnConversationWithACostCeiling() throws Exception {
        JsonNode out = parse(tools().scheduleFollowup(SOON, "check the approvals", null));

        assertTrue(out.get("booked").asBoolean(), out.toString());
        ScheduleConfiguration schedule = stored.get(0);
        assertEquals(SOON, schedule.getOneTimeAt());
        assertEquals(Instant.parse(SOON), schedule.getNextFire());
        assertEquals("agent-1", schedule.getAgentId());
        assertEquals("alice", schedule.getUserId());
        assertEquals("alice", schedule.getCreatedBy());
        assertEquals("persistent", schedule.getConversationStrategy());
        assertEquals("conv-1", schedule.getPersistentConversationId());
        assertEquals(AgentOpsConfig.DEFAULT_MAX_COST_PER_FIRE_USD, schedule.getMaxCostPerFire(), "never unlimited");
        assertFalse(schedule.isCreatorAdmin());
        assertEquals(List.of("team-a"), schedule.getCreatorTeams());
        assertEquals(FollowUpTools.METADATA_TYPE_VALUE, schedule.getMetadata().get(FollowUpTools.METADATA_TYPE_KEY));
    }

    @Test
    void theAgentTargetStartsAFreshConversation() throws Exception {
        assertTrue(parse(tools().scheduleFollowup(SOON, "again", "agent")).get("booked").asBoolean());
        assertEquals("new", stored.get(0).getConversationStrategy());
        assertNull(stored.get(0).getPersistentConversationId());
    }

    @Test
    void inAGroupDiscussionOnlyTheAgentTargetIsOffered() throws Exception {
        assertFalse(parse(tools(null, true).scheduleFollowup(SOON, "again", null)).get("booked").asBoolean());
        assertTrue(parse(tools(null, true).scheduleFollowup(SOON, "again", "agent")).get("booked").asBoolean());
    }

    @Test
    void theHorizonIsEnforcedAndNeverBeyondNinetyDays() throws Exception {
        assertTrue(parse(tools().scheduleFollowup("2026-12-04T12:00:00Z", "m", null)).get("booked").asBoolean(), "30 days is the default limit");
        assertFalse(parse(tools().scheduleFollowup("2026-12-04T12:00:01Z", "m", null)).get("booked").asBoolean());
        var limits = new AgentOpsConfig.FollowUp();
        limits.setMaxHorizonDays(AgentOpsConfig.MAX_HORIZON_DAYS);
        assertTrue(parse(tools(limits, false).scheduleFollowup("2027-02-02T12:00:00Z", "m", null)).get("booked").asBoolean(), "exactly 90 days");
        assertFalse(parse(tools(limits, false).scheduleFollowup("2027-02-02T12:00:01Z", "m", null)).get("booked").asBoolean());
    }

    @Test
    void aTimeInThePastOrWithinAMinuteIsRefused() throws Exception {
        assertFalse(parse(tools().scheduleFollowup("2026-11-04T11:00:00Z", "m", null)).get("booked").asBoolean());
        assertFalse(parse(tools().scheduleFollowup("2026-11-04T12:00:30Z", "m", null)).get("booked").asBoolean());
        assertTrue(parse(tools().scheduleFollowup("2026-11-04T12:01:00Z", "m", null)).get("booked").asBoolean());
    }

    @Test
    void anUnparseableTimeOrABlankOrOverlongMessageBooksNothing() throws Exception {
        assertFalse(parse(tools().scheduleFollowup("next tuesday", "m", null)).get("booked").asBoolean());
        assertFalse(parse(tools().scheduleFollowup(null, "m", null)).get("booked").asBoolean());
        assertFalse(parse(tools().scheduleFollowup(SOON, "  ", null)).get("booked").asBoolean());
        assertFalse(parse(tools().scheduleFollowup(SOON, "x".repeat(FollowUpTools.MAX_MESSAGE_CHARS + 1), null)).get("booked").asBoolean());
        assertFalse(parse(tools().scheduleFollowup(SOON, "m", "somewhere")).get("booked").asBoolean());
        verify(store, never()).createSchedule(any());
    }

    @Test
    void anOffsetTimeIsAccepted() throws Exception {
        assertTrue(parse(tools().scheduleFollowup("2026-11-10T10:00:00+01:00", "m", null)).get("booked").asBoolean());
        assertEquals(Instant.parse(SOON), stored.get(0).getNextFire());
    }

    @Test
    void oneTurnBooksAtMostMaxPerTurn() throws Exception {
        var tools = tools();
        assertTrue(parse(tools.scheduleFollowup(SOON, "one", null)).get("booked").asBoolean());
        assertTrue(parse(tools.scheduleFollowup(SOON, "two", null)).get("booked").asBoolean());
        JsonNode third = parse(tools.scheduleFollowup(SOON, "three", null));
        assertFalse(third.get("booked").asBoolean());
        assertEquals(2, stored.size());
    }

    @Test
    void anAgentMayHaveOnlyMaxPendingWaiting() throws Exception {
        var limits = new AgentOpsConfig.FollowUp();
        limits.setMaxPending(2);
        for (int i = 0; i < 2; i++) {
            assertTrue(parse(tools(limits, false).scheduleFollowup(SOON, "m" + i, null)).get("booked").asBoolean());
        }
        JsonNode third = parse(tools(limits, false).scheduleFollowup(SOON, "m3", null));
        assertFalse(third.get("booked").asBoolean());
        assertTrue(third.get("message").asText().contains("2 check-backs waiting"), third.toString());
        assertEquals(2, stored.size());
    }

    @Test
    void aFiredOrDisabledCheckBackNoLongerCountsAgainstTheCap() throws Exception {
        var limits = new AgentOpsConfig.FollowUp();
        limits.setMaxPending(1);
        tools(limits, false).scheduleFollowup(SOON, "first", null);
        stored.get(0).setNextFire(null);

        assertTrue(parse(tools(limits, false).scheduleFollowup(SOON, "second", null)).get("booked").asBoolean());
    }

    @Test
    void listShowsOnlyThisAgentsOwnCheckBacks() throws Exception {
        tools().scheduleFollowup(SOON, "mine", null);
        ScheduleConfiguration foreign = new ScheduleConfiguration();
        foreign.setId("foreign");
        foreign.setAgentId("agent-1");
        foreign.setNextFire(Instant.parse(SOON));
        foreign.setCreatedBy("alice");
        foreign.setMetadata(Map.of("dreamType", "dream_consolidation"));
        stored.add(foreign);

        JsonNode out = parse(tools().listFollowups());

        assertEquals(1, out.get("followups").size());
        assertEquals("mine", out.get("followups").get(0).get("message").asText());
    }

    @Test
    void anotherUsersCheckBackIsNeitherListedNorCancellable() throws Exception {
        tools().scheduleFollowup(SOON, "alices", null);
        var bob = new FollowUpTools(new FollowUpTools.Booker("agent-1", "bob", "conv-9", false, List.of()), null, store, CLOCK);

        assertEquals(0, parse(bob.listFollowups()).get("followups").size());
        assertFalse(parse(bob.cancelFollowup("sched-1")).has("cancelled"));
        verify(store, never()).deleteSchedule(anyString());
    }

    @Test
    void cancelDeletesOnlyTheNamedOwnCheckBack() throws Exception {
        tools().scheduleFollowup(SOON, "mine", null);

        assertTrue(parse(tools().cancelFollowup("sched-1")).get("cancelled").asBoolean());

        ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
        verify(store).deleteSchedule(id.capture());
        assertEquals("sched-1", id.getValue());
        assertFalse(parse(tools().cancelFollowup("not-mine")).has("cancelled"));
        assertFalse(parse(tools().cancelFollowup(" ")).has("cancelled"));
    }

    @Test
    void aStoreFailureBooksNothingAndSaysSo() throws Exception {
        doThrow(new IllegalStateException("db")).when(store).createSchedule(any());
        JsonNode out = parse(tools().scheduleFollowup(SOON, "m", null));
        assertFalse(out.get("booked").asBoolean());
    }
}
