/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.modules.llm.model.AgentOpsConfig.BudgetProposal;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig.FollowUp;
import ai.labs.eddi.modules.llm.model.AgentOpsConfig.Scorecard;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Save-time validation of {@link AgentOpsConfig}: every bound is refused with
 * the field path rather than clamped, so a designer never believes a limit the
 * runtime does not apply.
 */
public final class AgentOpsConfigValidator {

    private AgentOpsConfigValidator() {
    }

    public static void validate(AgentOpsConfig config, String fieldPath) {
        if (config == null) {
            return;
        }
        validateScorecard(config.getScorecard(), fieldPath + ".scorecard");
        validateBudgetProposal(config.getBudgetProposal(), fieldPath + ".budgetProposal");
        validateFollowUp(config.getFollowUp(), fieldPath + ".followUp");
    }

    private static void validateScorecard(Scorecard s, String path) {
        if (s == null) {
            return;
        }
        range(s.getWeeks(), 1, AgentOpsConfig.MAX_WEEKS, path + ".weeks");
        range(s.getMaxLessons(), 0, AgentOpsConfig.MAX_LESSONS, path + ".maxLessons");
        List<String> ids = s.getGroupIds();
        if (ids == null) {
            return;
        }
        if (ids.size() > AgentOpsConfig.MAX_EXTRA_GROUPS) {
            throw new IllegalArgumentException(path + ".groupIds lists more than " + AgentOpsConfig.MAX_EXTRA_GROUPS + " groups");
        }
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException(path + ".groupIds contains a blank id");
            }
            if (!seen.add(id.strip())) {
                throw new IllegalArgumentException(path + ".groupIds lists '" + id.strip() + "' twice");
            }
        }
    }

    private static void validateBudgetProposal(BudgetProposal b, String path) {
        if (b == null) {
            return;
        }
        range(b.getMaxPerTurn(), 1, AgentOpsConfig.MAX_PROPOSALS_PER_TURN, path + ".maxPerTurn");
        range(b.getMaxPerDay(), 1, AgentOpsConfig.MAX_PROPOSALS_PER_DAY, path + ".maxPerDay");
        positiveFinite(b.getMaxAmountUsd(), path + ".maxAmountUsd");
    }

    private static void validateFollowUp(FollowUp f, String path) {
        if (f == null) {
            return;
        }
        range(f.getMaxPending(), 1, AgentOpsConfig.MAX_PENDING, path + ".maxPending");
        range(f.getMaxPerTurn(), 1, AgentOpsConfig.MAX_PER_TURN, path + ".maxPerTurn");
        range(f.getMaxHorizonDays(), 1, AgentOpsConfig.MAX_HORIZON_DAYS, path + ".maxHorizonDays");
        positiveFinite(f.getMaxCostPerFireUsd(), path + ".maxCostPerFireUsd");
    }

    private static void range(Integer value, int min, int max, String path) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(path + " must be between " + min + " and " + max + " (was " + value + ")");
        }
    }

    private static void positiveFinite(Double value, String path) {
        if (value != null && (!Double.isFinite(value) || !(value > 0.0))) {
            throw new IllegalArgumentException(path + " must be a positive, finite dollar amount (was " + value + ")");
        }
    }
}
