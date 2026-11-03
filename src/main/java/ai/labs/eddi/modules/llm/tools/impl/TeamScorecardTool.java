/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.groups.budget.TeamBudgetService;
import ai.labs.eddi.engine.groups.budget.TeamBudgetSnapshot;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalAnalyticsService;
import ai.labs.eddi.engine.hitl.oversight.analytics.ApprovalSummary;
import ai.labs.eddi.engine.hitl.oversight.analytics.DecisionFilter;
import ai.labs.eddi.engine.internal.groups.RetroEngine;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code read_team_scorecard}: a read-only view of how a team is doing — spend
 * this month, budget left, backlog throughput per week, approval outcomes with
 * their reason codes, and the lessons its retros recorded.
 * <p>
 * It exists so an oversight agent can read the numbers a person used to paste
 * in. It reads the same stores the REST reads do, for the groups in an
 * {@link AgentOpsScope} — the agent's own group, the teams that group's budget
 * lets it oversee, and any the designer listed — and a group the conversation's
 * user may not view is left out of the answer, not shown empty. There is no
 * {@code userId} parameter: who is asking is the conversation's user.
 * <p>
 * Each section fails on its own: a store that is down costs the scorecard that
 * section ({@code "unavailable"}), never the whole answer.
 *
 * @since 6.7.0
 */
@Vetoed
public class TeamScorecardTool {

    private static final Logger LOGGER = Logger.getLogger(TeamScorecardTool.class);

    public static final String TOOL_NAME = "read_team_scorecard";

    static final int MAX_GROUPS = 10;
    static final int MAX_LESSON_CHARS = 400;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentOpsScope scope;
    private final AgentOpsConfig.Scorecard config;
    private final TeamBudgetService budgetService;
    private final IGroupWorkspaceStore workspaceStore;
    private final ApprovalAnalyticsService analytics;
    private final IUserMemoryStore userMemoryStore;
    private final Clock clock;

    public TeamScorecardTool(AgentOpsScope scope, AgentOpsConfig.Scorecard config, TeamBudgetService budgetService,
            IGroupWorkspaceStore workspaceStore, ApprovalAnalyticsService analytics, IUserMemoryStore userMemoryStore, Clock clock) {
        this.scope = scope;
        this.config = config != null ? config : new AgentOpsConfig.Scorecard();
        this.budgetService = budgetService;
        this.workspaceStore = workspaceStore;
        this.analytics = analytics;
        this.userMemoryStore = userMemoryStore;
        this.clock = clock;
    }

    @Tool(name = TOOL_NAME, value = "Read-only scorecard of your own team and any teams you oversee: spend this month, budget left, "
            + "backlog tasks done and failed per week, approval outcomes (approved, edited, rejected) with the reason codes "
            + "reviewers gave, and the lessons the team's retros recorded. Use it before proposing a budget change or "
            + "booking a follow-up. Teams you may not view are not listed.")
    public String readTeamScorecard(@P(value = "One team's group id, from a previous scorecard. Omit for every team you can read.",
                                       required = false) String groupId) {
        List<String> visible = scope.visible();
        if (groupId != null && !groupId.isBlank()) {
            String wanted = groupId.strip();
            // A group outside the scope and a group the user may not view answer the
            // same way, so the tool is not a probe for which group ids exist.
            if (!visible.contains(wanted)) {
                return json(Map.of("error", "No scorecard for that group: it is not one of your teams or a team you oversee, or you may not view it.",
                        "readable", visible));
            }
            visible = List.of(wanted);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", clock.instant().toString());
        out.put("weeks", config.effectiveWeeks());
        List<Map<String, Object>> teams = new ArrayList<>();
        for (String id : visible.stream().limit(MAX_GROUPS).toList()) {
            teams.add(scorecardOf(id));
        }
        out.put("teams", teams);
        if (visible.size() > MAX_GROUPS) {
            out.put("truncated", "Showing the first " + MAX_GROUPS + " of " + visible.size() + " teams; ask for one by groupId.");
        }
        if (teams.isEmpty()) {
            out.put("note", "You have no team to report on: you are not in a team discussion and no team is configured for this agent.");
        }
        return json(out);
    }

    Map<String, Object> scorecardOf(String groupId) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("groupId", groupId);
        AgentGroupConfiguration group = null;
        try {
            group = budgetService.config(groupId);
        } catch (RuntimeException e) {
            LOGGER.debugf("Scorecard: group %s unreadable: %s", LogSanitizer.sanitize(groupId), LogSanitizer.sanitize(e.getMessage()));
        }
        card.put("name", group != null ? group.getName() : null);
        card.put("budget", budgetSection(groupId, group));
        card.put("throughput", throughputSection(groupId));
        card.put("approvals", approvalSection(groupId));
        card.put("retroLessons", lessonSection(groupId));
        return card;
    }

    private Object budgetSection(String groupId, AgentGroupConfiguration group) {
        if (group == null || group.getTeamBudget() == null) {
            return Map.of("status", "none", "note", "This team has no team budget.");
        }
        try {
            TeamBudgetSnapshot s = budgetService.snapshot(groupId, group);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("month", s.month());
            m.put("capUsd", s.capUsd());
            m.put("spentUsd", s.spentUsd());
            m.put("remainingUsd", s.remainingUsd());
            m.put("exhausted", s.exhausted());
            m.put("limitedBy", s.limitedBy());
            m.put("resetsAt", s.resetsAt() == null ? null : s.resetsAt().toString());
            return m;
        } catch (RuntimeException e) {
            return unavailable("budget", groupId, e);
        }
    }

    private Object throughputSection(String groupId) {
        try {
            GroupWorkspace workspace = workspaceStore.find(groupId);
            if (workspace == null) {
                return Map.of("weeks", List.of(), "open", 0);
            }
            return throughput(workspace.getBacklog().all(), clock.instant(), config.effectiveWeeks());
        } catch (Exception e) {
            return unavailable("throughput", groupId, e);
        }
    }

    /**
     * Tasks finished (verified or completed) and failed per UTC week, newest week
     * last, for the last {@code weeks} weeks. A task counts in the week it was
     * completed; one with no completion time (a failure usually has none) counts in
     * the week it was created. Zero weeks are listed, so a stalled team shows as
     * zeros rather than as a short list.
     */
    static Map<String, Object> throughput(List<TaskItem> tasks, Instant now, int weeks) {
        LocalDate thisMonday = now.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<LocalDate, int[]> byWeek = new TreeMap<>();
        for (int i = weeks - 1; i >= 0; i--) {
            byWeek.put(thisMonday.minusWeeks(i), new int[2]);
        }
        int open = 0;
        for (TaskItem task : tasks) {
            TaskStatus status = task.status();
            boolean done = status == TaskStatus.VERIFIED || status == TaskStatus.COMPLETED;
            boolean failed = status == TaskStatus.FAILED;
            if (!done && !failed) {
                open++;
                continue;
            }
            Instant when = task.completedAt() != null ? task.completedAt() : task.createdAt();
            if (when == null) {
                continue;
            }
            int[] counts = byWeek.get(when.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
            if (counts != null) {
                counts[done ? 0 : 1]++;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        byWeek.forEach((monday, counts) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("weekStarting", monday.toString());
            row.put("done", counts[0]);
            row.put("failed", counts[1]);
            rows.add(row);
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weeks", rows);
        out.put("open", open);
        return out;
    }

    private Object approvalSection(String groupId) {
        try {
            Instant from = clock.instant().minus(Duration.ofDays(7L * config.effectiveWeeks()));
            ApprovalSummary.Result result = analytics.summarize(new DecisionFilter(null, groupId, null, from, null, null, null, null, null),
                    ApprovalAnalyticsService.GroupBy.NONE);
            return approvals(result, config.effectiveWeeks());
        } catch (RuntimeException e) {
            return unavailable("approvals", groupId, e);
        }
    }

    static Map<String, Object> approvals(ApprovalSummary.Result result, int weeks) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowWeeks", weeks);
        ApprovalSummary bucket = result == null || result.buckets() == null || result.buckets().isEmpty() ? null : result.buckets().get(0);
        if (bucket == null) {
            out.put("total", 0);
            return out;
        }
        Map<String, Long> byOutcome = bucket.byOutcome() == null ? Map.of() : bucket.byOutcome();
        out.put("total", bucket.total());
        out.put("approved", byOutcome.getOrDefault("APPROVED", 0L));
        out.put("approvedEdited", byOutcome.getOrDefault("APPROVED_EDITED", 0L));
        out.put("rejected", byOutcome.getOrDefault("REJECTED", 0L));
        out.put("byOutcome", byOutcome);
        out.put("editRate", bucket.editRate());
        out.put("rejectionRate", bucket.rejectionRate());
        out.put("topReasonCodes", bucket.topReasonCodes() == null
                ? List.of()
                : bucket.topReasonCodes().stream().map(c -> Map.of("code", c.code(), "count", c.count())).toList());
        if (result.truncated()) {
            out.put("truncated", true);
        }
        return out;
    }

    private Object lessonSection(String groupId) {
        int max = config.effectiveMaxLessons();
        if (max <= 0) {
            return List.of();
        }
        try {
            List<UserMemoryEntry> entries = userMemoryStore.getVisibleEntries(IUserMemoryStore.TEAM_OWNER_PREFIX + groupId,
                    RetroEngine.RETRO_SOURCE, List.of(groupId), "most_recent", max);
            List<Map<String, Object>> lessons = new ArrayList<>();
            for (UserMemoryEntry entry : entries.stream().limit(max).toList()) {
                String text = String.valueOf(entry.value());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("lesson", text.length() > MAX_LESSON_CHARS ? text.substring(0, MAX_LESSON_CHARS) + "…" : text);
                row.put("recordedAt", entry.updatedAt() == null ? null : entry.updatedAt().toString());
                lessons.add(row);
            }
            return lessons;
        } catch (Exception e) {
            return unavailable("retroLessons", groupId, e);
        }
    }

    private static Map<String, Object> unavailable(String section, String groupId, Exception e) {
        LOGGER.warnf("Scorecard: %s of group %s unavailable: %s", section, LogSanitizer.sanitize(groupId), LogSanitizer.sanitize(e.getMessage()));
        return Map.of("status", "unavailable", "note", "This section could not be read right now; do not treat it as zero.");
    }

    private static String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return "{\"error\":\"The scorecard could not be rendered.\"}";
        }
    }
}
