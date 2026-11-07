/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import java.util.List;

/**
 * Limits for the three groups of agent-operations tools —
 * {@code read_team_scorecard},
 * {@code propose_team_budget_raise}/{@code propose_team_budget_transfer} and
 * {@code schedule_followup}. Which of the tools an agent gets is decided by its
 * {@code builtInToolsWhitelist}: each is <b>off unless its slug is named
 * there</b> ({@code team_scorecard}, {@code propose_team_budget},
 * {@code schedule_followup}); an omitted whitelist does not admit them. This
 * block only tunes the limits, and every field has a default, so an absent
 * block (or an absent field) is a working configuration.
 *
 * @since 6.7.0
 */
public class AgentOpsConfig {

    /** Slug admitting {@code read_team_scorecard}. */
    public static final String SLUG_SCORECARD = "team_scorecard";
    /** Slug admitting the two {@code propose_team_budget_*} tools. */
    public static final String SLUG_BUDGET_PROPOSAL = "propose_team_budget";
    /** Slug admitting the follow-up tools. */
    public static final String SLUG_FOLLOW_UP = "schedule_followup";

    public static final int DEFAULT_WEEKS = 4;
    public static final int MAX_WEEKS = 12;
    public static final int DEFAULT_MAX_LESSONS = 5;
    public static final int MAX_LESSONS = 20;
    public static final int MAX_EXTRA_GROUPS = 20;

    public static final int DEFAULT_PROPOSALS_PER_TURN = 1;
    public static final int MAX_PROPOSALS_PER_TURN = 5;
    public static final int DEFAULT_PROPOSALS_PER_DAY = 3;
    public static final int MAX_PROPOSALS_PER_DAY = 50;
    public static final double DEFAULT_MAX_AMOUNT_USD = 25.0;

    public static final int DEFAULT_MAX_PENDING = 5;
    public static final int MAX_PENDING = 50;
    public static final int DEFAULT_MAX_PER_TURN = 2;
    public static final int MAX_PER_TURN = 10;
    public static final int DEFAULT_HORIZON_DAYS = 30;
    /** The longest check-back any configuration may allow. */
    public static final int MAX_HORIZON_DAYS = 90;
    public static final double DEFAULT_MAX_COST_PER_FIRE_USD = 0.5;

    private Scorecard scorecard;
    private BudgetProposal budgetProposal;
    private FollowUp followUp;

    public Scorecard getScorecard() {
        return scorecard;
    }

    public void setScorecard(Scorecard scorecard) {
        this.scorecard = scorecard;
    }

    public BudgetProposal getBudgetProposal() {
        return budgetProposal;
    }

    public void setBudgetProposal(BudgetProposal budgetProposal) {
        this.budgetProposal = budgetProposal;
    }

    public FollowUp getFollowUp() {
        return followUp;
    }

    public void setFollowUp(FollowUp followUp) {
        this.followUp = followUp;
    }

    /** Never null: an absent block reads as all defaults. */
    public static Scorecard scorecardOf(AgentOpsConfig config) {
        return config != null && config.scorecard != null ? config.scorecard : new Scorecard();
    }

    public static BudgetProposal budgetProposalOf(AgentOpsConfig config) {
        return config != null && config.budgetProposal != null ? config.budgetProposal : new BudgetProposal();
    }

    public static FollowUp followUpOf(AgentOpsConfig config) {
        return config != null && config.followUp != null ? config.followUp : new FollowUp();
    }

    /** What {@code read_team_scorecard} reads. */
    public static class Scorecard {
        private List<String> groupIds;
        private Integer weeks;
        private Integer maxLessons;

        /**
         * Extra groups to report on, for an agent that is not running inside a group
         * discussion (a weekly oversight agent). A group discussion's own group and the
         * teams its {@code teamBudget.oversight.teams} lists are always included. Every
         * group is still read only if the conversation's user may view it.
         */
        public List<String> getGroupIds() {
            return groupIds;
        }

        public void setGroupIds(List<String> groupIds) {
            this.groupIds = groupIds;
        }

        /** Weeks of throughput and approval history; default 4, at most 12. */
        public Integer getWeeks() {
            return weeks;
        }

        public void setWeeks(Integer weeks) {
            this.weeks = weeks;
        }

        /** Retro lessons returned per group; default 5, at most 20. */
        public Integer getMaxLessons() {
            return maxLessons;
        }

        public void setMaxLessons(Integer maxLessons) {
            this.maxLessons = maxLessons;
        }

        public int effectiveWeeks() {
            return weeks != null ? weeks : DEFAULT_WEEKS;
        }

        public int effectiveMaxLessons() {
            return maxLessons != null ? maxLessons : DEFAULT_MAX_LESSONS;
        }

        public List<String> effectiveGroupIds() {
            return groupIds != null ? groupIds : List.of();
        }
    }

    /** Caps on the {@code propose_team_budget_*} tools. */
    public static class BudgetProposal {
        private Integer maxPerTurn;
        private Integer maxPerDay;
        private Double maxAmountUsd;

        /** Calls the tool accepts in one turn; default 1. */
        public Integer getMaxPerTurn() {
            return maxPerTurn;
        }

        public void setMaxPerTurn(Integer maxPerTurn) {
            this.maxPerTurn = maxPerTurn;
        }

        /**
         * Applied proposals per agent in a rolling 24 hours; default 3. Counted from
         * the team budget's own adjustment history, so it survives restarts.
         */
        public Integer getMaxPerDay() {
            return maxPerDay;
        }

        public void setMaxPerDay(Integer maxPerDay) {
            this.maxPerDay = maxPerDay;
        }

        /** The most one proposal may add to (or move between) caps; default $25. */
        public Double getMaxAmountUsd() {
            return maxAmountUsd;
        }

        public void setMaxAmountUsd(Double maxAmountUsd) {
            this.maxAmountUsd = maxAmountUsd;
        }

        public int effectiveMaxPerTurn() {
            return maxPerTurn != null ? maxPerTurn : DEFAULT_PROPOSALS_PER_TURN;
        }

        public int effectiveMaxPerDay() {
            return maxPerDay != null ? maxPerDay : DEFAULT_PROPOSALS_PER_DAY;
        }

        public double effectiveMaxAmountUsd() {
            return maxAmountUsd != null ? maxAmountUsd : DEFAULT_MAX_AMOUNT_USD;
        }
    }

    /** Caps on the follow-up tools. */
    public static class FollowUp {
        private Integer maxPending;
        private Integer maxPerTurn;
        private Integer maxHorizonDays;
        private Double maxCostPerFireUsd;

        /**
         * Check-backs one agent may have waiting at once for one booking user; default
         * 5.
         */
        public Integer getMaxPending() {
            return maxPending;
        }

        public void setMaxPending(Integer maxPending) {
            this.maxPending = maxPending;
        }

        /** Check-backs one turn may book; default 2. */
        public Integer getMaxPerTurn() {
            return maxPerTurn;
        }

        public void setMaxPerTurn(Integer maxPerTurn) {
            this.maxPerTurn = maxPerTurn;
        }

        /**
         * How far ahead a check-back may be booked, in days; default 30, at most 90.
         */
        public Integer getMaxHorizonDays() {
            return maxHorizonDays;
        }

        public void setMaxHorizonDays(Integer maxHorizonDays) {
            this.maxHorizonDays = maxHorizonDays;
        }

        /**
         * The dollar ceiling every booked fire carries ({@code maxCostPerFire} of the
         * schedule); default $0.50. Never unlimited: a check-back an agent booked for
         * itself must not be able to spend without bound.
         */
        public Double getMaxCostPerFireUsd() {
            return maxCostPerFireUsd;
        }

        public void setMaxCostPerFireUsd(Double maxCostPerFireUsd) {
            this.maxCostPerFireUsd = maxCostPerFireUsd;
        }

        public int effectiveMaxPending() {
            return maxPending != null ? maxPending : DEFAULT_MAX_PENDING;
        }

        public int effectiveMaxPerTurn() {
            return maxPerTurn != null ? maxPerTurn : DEFAULT_MAX_PER_TURN;
        }

        public int effectiveMaxHorizonDays() {
            return maxHorizonDays != null ? maxHorizonDays : DEFAULT_HORIZON_DAYS;
        }

        public double effectiveMaxCostPerFireUsd() {
            return maxCostPerFireUsd != null ? maxCostPerFireUsd : DEFAULT_MAX_COST_PER_FIRE_USD;
        }
    }
}
