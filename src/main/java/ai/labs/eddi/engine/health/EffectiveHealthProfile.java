/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthDefaults.Definition;
import ai.labs.eddi.engine.health.HealthDefaults.Direction;
import ai.labs.eddi.engine.health.HealthDefaults.Preset;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthProfile.Budgets;
import ai.labs.eddi.engine.health.model.HealthProfile.Limits;
import ai.labs.eddi.engine.health.model.HealthProfile.MeasureOverride;
import ai.labs.eddi.engine.health.model.HealthProfile.Slo;
import ai.labs.eddi.engine.health.report.HealthReports.Thresholds;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * An agent's health profile as the measures and the status rules apply it: the
 * preset's defaults with the stored profile laid over them, field by field.
 * <ul>
 * <li><b>Critical</b>: the profile's list, else the preset's.</li>
 * <li><b>Thresholds</b>: the profile's limits, else — for
 * {@code agentFailureRate} with an SLO in the profile — green at the SLO's
 * error budget ({@code 1 − agentTurnSuccess}) and red at five times it, else
 * the preset's.</li>
 * <li><b>Budgets</b> make the cost measures scored: green up to the budget, red
 * from {@value #BUDGET_RED_FACTOR}× it.</li>
 * <li><b>Overrides</b> that have not expired (their {@code until} day is today
 * or later, UTC) leave a measure or a section out of the status; the measures
 * are still shown, with a hint.</li>
 * </ul>
 */
public final class EffectiveHealthProfile {

    /** The default reliability objective: 99 % of turns without an agent fault. */
    public static final double DEFAULT_AGENT_TURN_SUCCESS = 0.99;
    /** The default volume floor of a burn-rate alert, per window. */
    public static final int DEFAULT_MIN_TURNS_FOR_BURN_ALERT = 50;
    /** A budget's red limit, as a multiple of the budget. */
    public static final double BUDGET_RED_FACTOR = 1.5;
    /**
     * The SLO-derived red limit of agentFailureRate, as a multiple of the budget.
     */
    public static final double SLO_RED_FACTOR = 5.0;

    private final Preset preset;
    private final boolean stored;
    private final Set<String> critical;
    private final Map<String, Thresholds> thresholds;
    private final Budgets budgets;
    private final double agentTurnSuccess;
    private final Long apdexTMs;
    private final int minTurnsForBurnAlert;
    private final Map<String, MeasureOverride> ignoredMeasures;
    private final Map<String, MeasureOverride> ignoredSections;

    private EffectiveHealthProfile(Preset preset, boolean stored, Set<String> critical, Map<String, Thresholds> thresholds, Budgets budgets,
            double agentTurnSuccess, Long apdexTMs, int minTurnsForBurnAlert, Map<String, MeasureOverride> ignoredMeasures,
            Map<String, MeasureOverride> ignoredSections) {
        this.preset = preset;
        this.stored = stored;
        this.critical = critical;
        this.thresholds = thresholds;
        this.budgets = budgets;
        this.agentTurnSuccess = agentTurnSuccess;
        this.apdexTMs = apdexTMs;
        this.minTurnsForBurnAlert = minTurnsForBurnAlert;
        this.ignoredMeasures = ignoredMeasures;
        this.ignoredSections = ignoredSections;
    }

    /** A preset's defaults alone — an agent without a stored profile. */
    public static EffectiveHealthProfile ofPreset(String presetId) {
        return of(null, presetId, null);
    }

    /**
     * @param stored
     *            the stored profile, or {@code null}
     * @param autoPreset
     *            the preset picked from the agent's traffic, used when the profile
     *            names none
     * @param today
     *            the day overrides are checked against (UTC); {@code null} treats
     *            every override as expired
     */
    public static EffectiveHealthProfile of(HealthProfile stored, String autoPreset, LocalDate today) {
        String presetId = stored != null && stored.preset() != null && HealthDefaults.preset(stored.preset()) != null
                ? stored.preset()
                : autoPreset;
        Preset preset = HealthDefaults.preset(presetId);
        if (preset == null) {
            preset = HealthDefaults.PRESETS.get(HealthDefaults.PRESET_SUPPORT);
        }
        Set<String> critical = new LinkedHashSet<>(stored != null && stored.critical() != null ? stored.critical() : preset.critical());
        critical.removeIf(id -> HealthDefaults.of(id) == null);

        Slo slo = stored == null || stored.slo() == null ? Slo.NONE : stored.slo();
        double success = slo.agentTurnSuccess() != null ? slo.agentTurnSuccess() : DEFAULT_AGENT_TURN_SUCCESS;
        Map<String, Thresholds> thresholds = new LinkedHashMap<>(preset.thresholds());
        if (slo.agentTurnSuccess() != null) {
            double budget = 1 - slo.agentTurnSuccess();
            thresholds.put("agentFailureRate", new Thresholds(budget, Math.min(1.0, budget * SLO_RED_FACTOR), false));
        }
        if (stored != null && stored.thresholds() != null) {
            stored.thresholds().forEach((id, limits) -> {
                Definition d = HealthDefaults.of(id);
                if (d != null && limits != null && limits.green() != null && limits.red() != null) {
                    thresholds.put(id, new Thresholds(limits.green(), limits.red(), d.direction() == Direction.HIGHER));
                }
            });
        }
        Budgets budgets = stored == null || stored.budgets() == null ? Budgets.NONE : stored.budgets();
        budget(thresholds, "costPerResolvedSession", budgets.costPerResolvedUsd());
        budget(thresholds, "costPerSession", budgets.costPerSessionUsd());
        budget(thresholds, "tokensPerTurn", budgets.tokensPerTurn());

        Map<String, MeasureOverride> measures = new LinkedHashMap<>();
        Map<String, MeasureOverride> sections = new LinkedHashMap<>();
        if (stored != null && stored.overrides() != null && today != null) {
            for (MeasureOverride o : stored.overrides()) {
                if (o == null || !MeasureOverride.IGNORED.equals(o.state()) || !active(o, today)) {
                    continue;
                }
                if (o.measure() != null) {
                    measures.putIfAbsent(o.measure(), o);
                } else if (o.section() != null) {
                    sections.putIfAbsent(o.section(), o);
                }
            }
        }
        int minTurns = slo.minTurnsForBurnAlert() != null ? slo.minTurnsForBurnAlert() : DEFAULT_MIN_TURNS_FOR_BURN_ALERT;
        return new EffectiveHealthProfile(preset, stored != null, Collections.unmodifiableSet(critical), Collections.unmodifiableMap(thresholds),
                budgets, success, slo.apdexTMs(), minTurns, Collections.unmodifiableMap(measures), Collections.unmodifiableMap(sections));
    }

    private static void budget(Map<String, Thresholds> thresholds, String measure, Double budget) {
        if (budget != null && budget > 0) {
            thresholds.put(measure, new Thresholds(budget, budget * BUDGET_RED_FACTOR, false));
        }
    }

    /** Whether an override still applies on {@code today}. */
    static boolean active(MeasureOverride o, LocalDate today) {
        if (o.until() == null) {
            return false;
        }
        try {
            return !LocalDate.parse(o.until().trim()).isBefore(today);
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    public String preset() {
        return preset.id();
    }

    public boolean stored() {
        return stored;
    }

    public boolean critical(String measureId) {
        return critical.contains(measureId);
    }

    public Set<String> criticalMeasures() {
        return critical;
    }

    /** The band limits of a measure, or {@code null} for none. */
    public Thresholds thresholds(String measureId) {
        return thresholds.get(measureId);
    }

    /**
     * The measure's kind: its definition's, except that a budget turns a cost
     * measure into a scored one.
     */
    public String kind(Definition d) {
        if (HealthDefaults.INFORMATIONAL.equals(d.kind()) && HealthDefaults.EFFICIENCY.equals(d.section()) && thresholds.containsKey(d.id())) {
            return HealthDefaults.SCORED;
        }
        return d.kind();
    }

    /** The override that leaves the measure out of the status, or {@code null}. */
    public MeasureOverride ignored(String measureId) {
        MeasureOverride o = ignoredMeasures.get(measureId);
        if (o != null) {
            return o;
        }
        Definition d = HealthDefaults.of(measureId);
        return d == null ? null : ignoredSections.get(d.section());
    }

    public double agentTurnSuccess() {
        return agentTurnSuccess;
    }

    /** The agent's Apdex T, or {@code null} for the deployment's. */
    public Long apdexTMs() {
        return apdexTMs;
    }

    public int minTurnsForBurnAlert() {
        return minTurnsForBurnAlert;
    }

    /**
     * The profile as a response: the effective values, so writing back what was
     * read keeps the meaning.
     */
    public HealthProfile toResponse(String agentId, HealthProfile storedProfile) {
        Map<String, Limits> limits = new LinkedHashMap<>();
        thresholds.forEach((id, t) -> {
            if (!HealthDefaults.EFFICIENCY.equals(HealthDefaults.of(id).section())) {
                limits.put(id, new Limits(t.green(), t.red()));
            }
        });
        List<MeasureOverride> overrides = storedProfile == null || storedProfile.overrides() == null
                ? List.of()
                : new ArrayList<>(storedProfile.overrides());
        Slo stored = storedProfile == null || storedProfile.slo() == null ? Slo.NONE : storedProfile.slo();
        return new HealthProfile(agentId, preset.id(), List.copyOf(critical), limits, budgets,
                new Slo(agentTurnSuccess, stored.apdexTMs(), minTurnsForBurnAlert), overrides, storedProfile != null,
                storedProfile == null ? null : storedProfile.updatedAt(), storedProfile == null ? null : storedProfile.updatedBy(),
                HealthProfile.defaultedFields(storedProfile));
    }
}
