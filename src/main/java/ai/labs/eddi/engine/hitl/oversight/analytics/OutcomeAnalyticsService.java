/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.analytics;

import ai.labs.eddi.engine.groups.outcomes.IOutcomeEventStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What came of the approvals: a team's approval decisions in a window, joined
 * with the outcomes its work reported, per approval category. An approval gate
 * records that a human said yes; this shows whether yes was right.
 * <p>
 * An outcome attaches to a decision either directly (subject type
 * {@code DECISION}, the decision id) or through the task a group-task decision
 * approved (subject type {@code TASK}, the task id, which is the decision
 * unit's {@code unitId}). The join is scoped to the team: an outcome recorded
 * under one team never counts toward another team's decisions, whatever id it
 * names.
 *
 * @since 6.8.0
 */
@ApplicationScoped
public class OutcomeAnalyticsService {

    /** Most decision rows one report reads. */
    static final int MAX_DECISIONS = ApprovalAnalyticsService.MAX_ROWS;
    /** Most outcome events one report reads. */
    static final int MAX_OUTCOMES = 20_000;
    static final int KEY_BATCH = 500;

    /**
     * One category's line.
     *
     * @param decisions
     *            decided units in the window
     * @param decisionsWithOutcomes
     *            of those, how many have at least one outcome
     * @param outcomes
     *            outcome events attached to them
     * @param byKind
     *            outcome events by kind
     */
    public record CategoryOutcomes(String category, long decisions, long decisionsWithOutcomes, long outcomes, Map<String, Long> byKind) {
    }

    /** The report. {@code truncated} when a read cap cut it short. */
    public record Result(String groupId, long decisions, long outcomes, boolean truncated, List<CategoryOutcomes> categories) {
    }

    private final IApprovalDecisionStore decisionStore;
    private final IOutcomeEventStore outcomeStore;

    @Inject
    public OutcomeAnalyticsService(IApprovalDecisionStore decisionStore, IOutcomeEventStore outcomeStore) {
        this.decisionStore = decisionStore;
        this.outcomeStore = outcomeStore;
    }

    /** The report of the decisions matching the filter, which must name a group. */
    public Result summarize(DecisionFilter filter) {
        String groupId = filter.groupId();
        if (groupId == null) {
            throw new IllegalArgumentException("groupId is required: outcomes belong to a team");
        }
        List<ApprovalDecision> rows = decisionStore.query(filter, 0, MAX_DECISIONS + 1);
        boolean truncated = rows.size() > MAX_DECISIONS;
        if (truncated) {
            rows = rows.subList(0, MAX_DECISIONS);
        }

        // subject id -> indexes of the decisions it can mean
        Map<String, List<Integer>> bySubject = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            ApprovalDecision d = rows.get(i);
            bySubject.computeIfAbsent(d.decisionId(), k -> new ArrayList<>()).add(i);
            if ("TASK".equals(d.unitKind()) && d.unitId() != null) {
                bySubject.computeIfAbsent(d.unitId(), k -> new ArrayList<>()).add(i);
            }
        }
        List<OutcomeEvent> events = new ArrayList<>();
        List<String> keys = new ArrayList<>(bySubject.keySet());
        for (int from = 0; from < keys.size() && events.size() < MAX_OUTCOMES; from += KEY_BATCH) {
            events.addAll(outcomeStore.listBySubjects(groupId, keys.subList(from, Math.min(keys.size(), from + KEY_BATCH)),
                    MAX_OUTCOMES - events.size()));
        }
        if (events.size() >= MAX_OUTCOMES) {
            truncated = true;
        }

        Map<String, long[]> counters = new TreeMap<>();
        Map<String, Map<String, Long>> kinds = new TreeMap<>();
        Map<String, Set<Integer>> withOutcome = new TreeMap<>();
        for (ApprovalDecision d : rows) {
            counters.computeIfAbsent(d.category(), k -> new long[3])[0]++;
        }
        long attached = 0;
        for (OutcomeEvent e : events) {
            Set<String> categories = new HashSet<>();
            for (int index : bySubject.getOrDefault(e.subjectId(), List.of())) {
                ApprovalDecision d = rows.get(index);
                withOutcome.computeIfAbsent(d.category(), k -> new HashSet<>()).add(index);
                if (categories.add(d.category())) {
                    counters.get(d.category())[2]++;
                    kinds.computeIfAbsent(d.category(), k -> new TreeMap<>()).merge(e.kind(), 1L, Long::sum);
                }
            }
            if (!categories.isEmpty()) {
                attached++;
            }
        }
        List<CategoryOutcomes> categories = new ArrayList<>();
        counters.forEach((category, c) -> categories.add(new CategoryOutcomes(category, c[0],
                withOutcome.getOrDefault(category, Set.of()).size(), c[2], kinds.getOrDefault(category, Map.of()))));
        categories.sort(Comparator.comparingLong(CategoryOutcomes::decisions).reversed().thenComparing(CategoryOutcomes::category));
        return new Result(groupId, rows.size(), attached, truncated, categories);
    }
}
