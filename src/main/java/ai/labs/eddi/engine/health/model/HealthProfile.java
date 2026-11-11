/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * An agent's health profile (plan §4.5, contract §5): how its health is judged
 * — the preset, the critical measures, band limits, budgets, the SLO and
 * temporary overrides. <b>Unversioned</b>: tuning a threshold must not mint an
 * agent version and split its statistics, so the profile is an admin resource
 * keyed by the agent id, not part of {@code AgentConfiguration}. It does not
 * travel with agent export or sync — thresholds are a deployment's own.
 * <p>
 * As a request body every field is optional: a missing one falls back to the
 * preset (or the deployment default). As a response it is the
 * <em>effective</em> profile — the preset merged with what is stored — so a
 * client that writes back what it read keeps the meaning; {@code stored} says
 * whether anything is stored at all.
 *
 * @param preset
 *            {@code support}, {@code sales}, {@code assistant} or {@code task};
 *            {@code null} on a request picks it from the agent's traffic
 *            ({@code task} for an agent driven only by schedules, webhooks, A2A
 *            or groups, else {@code support})
 * @param critical
 *            the measures whose red band makes the status
 *            {@code NEEDS_ATTENTION}; {@code null} keeps the preset's, an empty
 *            list has none
 * @param thresholds
 *            band limits by measure id, over the preset's
 * @param budgets
 *            spend limits that turn the cost measures into scored ones
 * @param slo
 *            the reliability objective, the Apdex threshold and the burn-alert
 *            volume floor
 * @param overrides
 *            measures or sections to leave out of the status until a day, each
 *            with a reason; an expired one is ignored, not refused
 * @param stored
 *            response only: whether a profile is stored for the agent
 * @param updatedAt
 *            response only: when it was last written (ISO-8601), or
 *            {@code null}
 * @param updatedBy
 *            response only: who wrote it last, or {@code null}
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
@JsonIgnoreProperties(ignoreUnknown = true)
public record HealthProfile(String agentId, String preset, List<String> critical, Map<String, Limits> thresholds, Budgets budgets, Slo slo,
        List<MeasureOverride> overrides, Boolean stored, String updatedAt, String updatedBy) {

    /**
     * Band limits of one measure. Whether higher is better comes from the measure,
     * so a client cannot invert it by accident.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Limits(Double green, Double red) {
    }

    /**
     * Spend limits. Each one set turns its measure into a scored one, banded green
     * up to the budget and red from one and a half times it.
     *
     * @param costPerResolvedUsd
     *            {@code costPerResolvedSession}
     * @param costPerSessionUsd
     *            {@code costPerSession} (for a {@code task} agent: per run)
     * @param tokensPerTurn
     *            {@code tokensPerTurn}
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Budgets(Double costPerResolvedUsd, Double costPerSessionUsd, Double tokensPerTurn) {
        public static final Budgets NONE = new Budgets(null, null, null);
    }

    /**
     * @param agentTurnSuccess
     *            the share of turns that must not fail through the agent's own
     *            fault (0.99 by default); its error budget sets the
     *            {@code agentFailureRate} bands unless they are given, and the burn
     *            rate of {@code HEALTH} alerts
     * @param apdexTMs
     *            the Apdex threshold T for this agent; {@code null} is
     *            {@code eddi.health.apdex-t}
     * @param minTurnsForBurnAlert
     *            the volume floor of a burn-rate alert: each window needs at least
     *            this many turns (50 by default)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slo(Double agentTurnSuccess, Long apdexTMs, Integer minTurnsForBurnAlert) {
        public static final Slo NONE = new Slo(null, null, null);
    }

    /**
     * Leaves one measure, or a whole section, out of the status until a day — a CRM
     * outage that empties the business section, a known incident.
     *
     * @param measure
     *            a measure id, or {@code null} when {@code section} is set
     * @param section
     *            a section name, or {@code null} when {@code measure} is set
     * @param state
     *            {@code IGNORED}, the only state today
     * @param until
     *            the last day (UTC, {@code YYYY-MM-DD}, inclusive) it applies
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MeasureOverride(String measure, String section, String state, String reason, String until) {
        public static final String IGNORED = "IGNORED";
    }

    /** The same profile with its bookkeeping fields set. */
    public HealthProfile withMeta(String newAgentId, Boolean newStored, String newUpdatedAt, String newUpdatedBy) {
        return new HealthProfile(newAgentId, preset, critical, thresholds, budgets, slo, overrides, newStored, newUpdatedAt, newUpdatedBy);
    }
}
