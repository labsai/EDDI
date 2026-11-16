/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig.CostPolicy;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.groups.model.TeamBudgetConfig;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.groups.budget.TeamBudgetAccountingUnavailableException;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.groups.budget.TeamBudgetSnapshot;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalAnalyticsService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalSummary;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamScorecardToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // a Wednesday
    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private TeamBudgetService budget;
    private IGroupWorkspaceStore workspaces;
    private ApprovalAnalyticsService analytics;
    private IUserMemoryStore memory;

    @BeforeEach
    void setUp() throws Exception {
        budget = mock(TeamBudgetService.class);
        workspaces = mock(IGroupWorkspaceStore.class);
        analytics = mock(ApprovalAnalyticsService.class);
        memory = mock(IUserMemoryStore.class);
        AgentGroupConfiguration config = new AgentGroupConfiguration();
        config.setName("Content Studio");
        config.setTeamBudget(new TeamBudgetConfig(35.0, CostPolicy.SYNTHESIZE_NOW, null));
        when(budget.config(anyString())).thenReturn(config);
        when(budget.snapshot(anyString(), any(AgentGroupConfiguration.class)))
                .thenReturn(new TeamBudgetSnapshot("studio", "Content Studio", "2026-11",
                        35.0, 0.0, 35.0, 12.5, 22.5, false, Instant.parse("2026-12-01T00:00:00Z"), null, "TEAM"));
        when(workspaces.find(anyString())).thenReturn(null);
        when(analytics.summarize(any(), any())).thenReturn(new ApprovalSummary.Result("none", false, List.of()));
        when(memory.getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt())).thenReturn(List.of());
    }

    private TeamScorecardTool tool(AgentOpsScope scope, AgentOpsConfig.Scorecard config) {
        return new TeamScorecardTool(scope, config, budget, workspaces, analytics, memory, CLOCK);
    }

    private TeamScorecardTool tool(AgentOpsScope scope) {
        return tool(scope, null);
    }

    private static AgentOpsScope scopeOf(String... ids) {
        return new AgentOpsScope(List.of(ids), id -> true);
    }

    private static JsonNode parse(String json) throws Exception {
        return MAPPER.readTree(json);
    }

    private static TaskItem task(TaskStatus status, String completedAt, String createdAt) {
        return new TaskItem("t-" + status + completedAt, "s", "d", status, null, null, List.of(), null, null, false, 0, Instant.parse(createdAt),
                completedAt == null ? null : Instant.parse(completedAt));
    }

    @Test
    void reportsBudgetThroughputApprovalsAndLessonsForTheTeam() throws Exception {
        GroupWorkspace workspace = new GroupWorkspace();
        workspace.getBacklog().addTask(task(TaskStatus.VERIFIED, "2026-11-03T10:00:00Z", "2026-10-30T10:00:00Z"));
        workspace.getBacklog().addTask(task(TaskStatus.PENDING, null, "2026-11-01T10:00:00Z"));
        when(workspaces.find("studio")).thenReturn(workspace);
        when(analytics.summarize(any(), any())).thenReturn(new ApprovalSummary.Result("none", false,
                List.of(new ApprovalSummary("all", 10, Map.of("APPROVED", 6L, "APPROVED_EDITED", 3L, "REJECTED", 1L), 10, 0.6, 0.3, 0.1,
                        List.of(new ApprovalSummary.CodeCount("TONE", 2)), null, null, null, null, null, 0, null))));
        when(memory.getVisibleEntries(eq("group:studio"), eq("retro"), eq(List.of("studio")), anyString(), anyInt()))
                .thenReturn(List.of(new UserMemoryEntry("1", "group:studio", "retro:a", "Ship shorter posts", "context", null, "retro",
                        List.of("studio"), null, false, 0, NOW, NOW)));

        JsonNode card = parse(tool(scopeOf("studio")).readTeamScorecard(null)).get("teams").get(0);

        assertEquals("Content Studio", card.get("name").asText());
        assertEquals(12.5, card.get("budget").get("spentUsd").asDouble());
        assertEquals(22.5, card.get("budget").get("remainingUsd").asDouble());
        assertEquals(1, card.get("throughput").get("weeks").get(3).get("done").asInt(), "the week of 2026-11-02");
        assertEquals(1, card.get("throughput").get("open").asInt());
        assertEquals(3, card.get("approvals").get("approvedEdited").asInt());
        assertEquals(1, card.get("approvals").get("rejected").asInt());
        assertEquals("TONE", card.get("approvals").get("topReasonCodes").get(0).get("code").asText());
        assertEquals("Ship shorter posts", card.get("retroLessons").get(0).get("lesson").asText());
    }

    @Test
    void throughputBucketsByUtcWeekAndListsEmptyWeeks() {
        var tasks = List.of(task(TaskStatus.COMPLETED, "2026-11-02T00:00:00Z", "2026-10-01T00:00:00Z"),
                task(TaskStatus.VERIFIED, "2026-11-01T23:59:59Z", "2026-10-01T00:00:00Z"),
                task(TaskStatus.FAILED, null, "2026-10-21T08:00:00Z"),
                task(TaskStatus.VERIFIED, "2026-08-01T00:00:00Z", "2026-08-01T00:00:00Z"));

        @SuppressWarnings("unchecked")
        var weeks = (List<Map<String, Object>>) TeamScorecardTool.throughput(tasks, NOW, 4).get("weeks");

        assertEquals(4, weeks.size());
        assertEquals("2026-10-12", weeks.get(0).get("weekStarting"));
        assertEquals(0, weeks.get(0).get("done"));
        assertEquals(1, weeks.get(1).get("failed"), "a failure without a completion time counts in its creation week");
        assertEquals(1, weeks.get(2).get("done"), "Sunday 23:59:59 belongs to the previous week");
        assertEquals(1, weeks.get(3).get("done"), "Monday 00:00:00 starts the new week");
    }

    @Test
    void aGroupTheUserMayNotViewIsLeftOutAndCannotBeAskedFor() throws Exception {
        var scope = new AgentOpsScope(List.of("studio", "secret"), id -> !id.equals("secret"));
        TeamScorecardTool tool = tool(scope);

        JsonNode all = parse(tool.readTeamScorecard(null));
        JsonNode asked = parse(tool.readTeamScorecard("secret"));

        assertEquals(1, all.get("teams").size());
        assertEquals("studio", all.get("teams").get(0).get("groupId").asText());
        assertTrue(asked.has("error"), asked.toString());
        assertFalse(asked.toString().contains("secret\""), "the answer does not confirm the group exists: " + asked);
        verify(workspaces, never()).find("secret");
        verify(analytics, never()).summarize(argThat(f -> f != null && "secret".equals(f.groupId())), any());
    }

    @Test
    void aGroupOutsideTheScopeAnswersLikeOneTheUserMayNotView() throws Exception {
        TeamScorecardTool tool = tool(scopeOf("studio"));
        assertTrue(parse(tool.readTeamScorecard("some-other-group")).has("error"));
    }

    @Test
    void aViewCheckThatThrowsFailsClosed() throws Exception {
        var scope = new AgentOpsScope(List.of("studio"), id -> {
            throw new IllegalStateException("guard down");
        });
        JsonNode out = parse(tool(scope).readTeamScorecard(null));
        assertEquals(0, out.get("teams").size());
    }

    @Test
    void oneFailingSectionDoesNotCostTheOthers() throws Exception {
        when(budget.snapshot(anyString(), any(AgentGroupConfiguration.class))).thenThrow(new TeamBudgetAccountingUnavailableException("down"));
        when(workspaces.find(anyString())).thenThrow(new IllegalStateException("db"));

        JsonNode card = parse(tool(scopeOf("studio")).readTeamScorecard(null)).get("teams").get(0);

        assertEquals("unavailable", card.get("budget").get("status").asText());
        assertEquals("unavailable", card.get("throughput").get("status").asText());
        assertTrue(card.get("approvals").has("total"), "approvals still read");
    }

    @Test
    void aGroupWithoutATeamBudgetSaysSoInsteadOfZero() throws Exception {
        when(budget.config(anyString())).thenReturn(new AgentGroupConfiguration());
        JsonNode budgetNode = parse(tool(scopeOf("studio")).readTeamScorecard(null)).get("teams").get(0).get("budget");
        assertEquals("none", budgetNode.get("status").asText());
        assertFalse(budgetNode.has("spentUsd"));
    }

    @Test
    void noScopeAtAllExplainsItself() throws Exception {
        JsonNode out = parse(tool(scopeOf()).readTeamScorecard(null));
        assertEquals(0, out.get("teams").size());
        assertTrue(out.has("note"));
    }

    @Test
    void lessonsCanBeTurnedOffAndAreCapped() throws Exception {
        var config = new AgentOpsConfig.Scorecard();
        config.setMaxLessons(0);
        JsonNode card = parse(tool(scopeOf("studio"), config).readTeamScorecard(null)).get("teams").get(0);
        assertEquals(0, card.get("retroLessons").size());
        verify(memory, never()).getVisibleEntries(anyString(), anyString(), anyList(), anyString(), anyInt());
    }

    @Test
    void theApprovalWindowStartsOnTheMondayOfTheOldestThroughputWeek() throws Exception {
        var config = new AgentOpsConfig.Scorecard();
        config.setWeeks(2);
        tool(scopeOf("studio"), config).readTeamScorecard(null);
        // NOW is Wednesday 2026-11-04: the two weeks shown start on Mondays 10-26 and
        // 11-02
        Instant firstMonday = Instant.parse("2026-10-26T00:00:00Z");
        verify(analytics).summarize(argThat(f -> f != null && "studio".equals(f.groupId()) && firstMonday.equals(f.from())), any());
        @SuppressWarnings("unchecked")
        var weeks = (List<Map<String, Object>>) TeamScorecardTool.throughput(List.of(), NOW, 2).get("weeks");
        assertEquals("2026-10-26", weeks.get(0).get("weekStarting"), "both sections cover the same weeks");
    }

    @Test
    void atMostTenTeamsAreAnsweredAtOnce() throws Exception {
        String[] ids = new String[12];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "g" + i;
        }
        JsonNode out = parse(tool(scopeOf(ids)).readTeamScorecard(null));
        assertEquals(TeamScorecardTool.MAX_GROUPS, out.get("teams").size());
        assertTrue(out.has("truncated"));
    }
}
