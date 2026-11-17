/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthDefaults.Definition;
import ai.labs.eddi.engine.health.HealthDefaults.Direction;
import ai.labs.eddi.engine.health.model.HealthProfile;
import ai.labs.eddi.engine.health.model.HealthProfile.Budgets;
import ai.labs.eddi.engine.health.model.HealthProfile.Limits;
import ai.labs.eddi.engine.health.model.HealthProfile.MeasureOverride;
import ai.labs.eddi.engine.health.model.HealthProfile.Slo;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Save-time validation of a health profile. Every problem is reported with a
 * dotted path into the body — {@code thresholds.resolutionRate.red},
 * {@code overrides[0].reason} — so a client can show it under the field it
 * concerns (contract §5, §11.3). An expired override is <em>not</em> a problem:
 * it is ignored when the status is computed.
 */
public final class HealthProfileValidator {

    public static final int MAX_CRITICAL = 10;
    public static final int MAX_OVERRIDES = 50;
    public static final int MAX_REASON_LENGTH = 200;
    /** How far ahead an override may run, in days. */
    public static final int MAX_OVERRIDE_DAYS = 366;

    /** One problem: where, and what. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Violation(String path, String message) {
    }

    private HealthProfileValidator() {
    }

    /**
     * @param agentId
     *            the agent of the request path; a body naming another is refused
     * @param today
     *            the day the override limits are counted from (UTC)
     * @return the problems, empty when the profile may be stored
     */
    public static List<Violation> validate(String agentId, HealthProfile profile, LocalDate today) {
        List<Violation> v = new ArrayList<>();
        if (profile == null) {
            v.add(new Violation("", "a profile body is required"));
            return v;
        }
        if (profile.agentId() != null && !profile.agentId().equals(agentId)) {
            v.add(new Violation("agentId", "names another agent than the request path"));
        }
        if (profile.preset() != null && HealthDefaults.preset(profile.preset()) == null) {
            v.add(new Violation("preset", "must be one of " + String.join(", ", HealthDefaults.PRESETS.keySet())));
        }
        budgetsOk(profile.budgets(), v);
        critical(profile, v);
        thresholds(profile.thresholds(), v);
        slo(profile.slo(), v);
        overrides(profile.overrides(), today, v);
        return v;
    }

    private static void critical(HealthProfile profile, List<Violation> v) {
        List<String> critical = profile.critical();
        if (critical == null) {
            return;
        }
        if (critical.size() > MAX_CRITICAL) {
            v.add(new Violation("critical", "may list at most " + MAX_CRITICAL + " measures"));
        }
        Set<String> seen = new HashSet<>();
        Budgets budgets = profile.budgets() == null ? Budgets.NONE : profile.budgets();
        for (int i = 0; i < critical.size(); i++) {
            String id = critical.get(i);
            String path = "critical[" + i + "]";
            Definition d = id == null ? null : HealthDefaults.of(id);
            if (d == null) {
                v.add(new Violation(path, "is not a known measure"));
                continue;
            }
            if (!seen.add(id)) {
                v.add(new Violation(path, "lists " + id + " twice"));
            }
            if (HealthDefaults.QUALITY.equals(d.section())) {
                v.add(new Violation(path, "quality measures do not drive the status (who may read ratings differs per caller)"));
            } else if (!scorable(d, budgets)) {
                v.add(new Violation(path, id + " is informational and cannot be critical"
                        + (HealthDefaults.EFFICIENCY.equals(d.section()) ? " unless a budget for it is set" : "")));
            }
        }
    }

    /** Whether the measure has a band: scored, or a cost measure with a budget. */
    private static boolean scorable(Definition d, Budgets budgets) {
        if (HealthDefaults.SCORED.equals(d.kind())) {
            return true;
        }
        return switch (d.id()) {
            case "costPerResolvedSession" -> budgets.costPerResolvedUsd() != null;
            case "costPerSession" -> budgets.costPerSessionUsd() != null;
            case "tokensPerTurn" -> budgets.tokensPerTurn() != null;
            default -> false;
        };
    }

    private static void thresholds(Map<String, Limits> thresholds, List<Violation> v) {
        if (thresholds == null) {
            return;
        }
        for (Map.Entry<String, Limits> entry : thresholds.entrySet()) {
            String id = entry.getKey();
            String path = "thresholds." + id;
            Definition d = id == null ? null : HealthDefaults.of(id);
            if (d == null) {
                v.add(new Violation(path, "is not a known measure"));
                continue;
            }
            if (HealthDefaults.EFFICIENCY.equals(d.section())) {
                v.add(new Violation(path, "set a budget for " + id + " under budgets instead"));
                continue;
            }
            if (!HealthDefaults.SCORED.equals(d.kind()) || d.direction() == Direction.NONE) {
                v.add(new Violation(path, id + " is informational and has no bands"));
                continue;
            }
            Limits limits = entry.getValue();
            if (limits == null) {
                v.add(new Violation(path, "needs green and red"));
                continue;
            }
            boolean greenOk = limit(path + ".green", limits.green(), d, v);
            boolean redOk = limit(path + ".red", limits.red(), d, v);
            if (greenOk && redOk) {
                if (d.direction() == Direction.HIGHER && !(limits.red() < limits.green())) {
                    v.add(new Violation(path + ".red", "must be below green (higher is better for " + id + ")"));
                } else if (d.direction() == Direction.LOWER && !(limits.red() > limits.green())) {
                    v.add(new Violation(path + ".red", "must be above green (lower is better for " + id + ")"));
                }
            }
        }
    }

    private static boolean limit(String path, Double value, Definition d, List<Violation> v) {
        if (value == null) {
            v.add(new Violation(path, "is required"));
            return false;
        }
        if (value.isNaN() || value.isInfinite()) {
            v.add(new Violation(path, "must be a number"));
            return false;
        }
        if (("ratio".equals(d.unit()) || "score".equals(d.unit())) && (value < 0 || value > 1)) {
            v.add(new Violation(path, "must be between 0 and 1"));
            return false;
        }
        return true;
    }

    private static void budgetsOk(Budgets budgets, List<Violation> v) {
        if (budgets == null) {
            return;
        }
        positive("budgets.costPerResolvedUsd", budgets.costPerResolvedUsd(), 1_000_000, v);
        positive("budgets.costPerSessionUsd", budgets.costPerSessionUsd(), 1_000_000, v);
        positive("budgets.tokensPerTurn", budgets.tokensPerTurn(), 10_000_000, v);
    }

    private static void positive(String path, Double value, double max, List<Violation> v) {
        if (value != null && (value.isNaN() || !(value > 0) || value > max)) {
            v.add(new Violation(path, "must be above 0 and at most " + (long) max));
        }
    }

    private static void slo(Slo slo, List<Violation> v) {
        if (slo == null) {
            return;
        }
        Double success = slo.agentTurnSuccess();
        if (success != null && (success.isNaN() || success < 0.5 || success >= 1)) {
            v.add(new Violation("slo.agentTurnSuccess", "must be at least 0.5 and below 1 (e.g. 0.99)"));
        }
        if (slo.apdexTMs() != null && (slo.apdexTMs() < 100 || slo.apdexTMs() > 600_000)) {
            v.add(new Violation("slo.apdexTMs", "must be between 100 and 600000 milliseconds"));
        }
        if (slo.minTurnsForBurnAlert() != null && (slo.minTurnsForBurnAlert() < 1 || slo.minTurnsForBurnAlert() > 1_000_000)) {
            v.add(new Violation("slo.minTurnsForBurnAlert", "must be between 1 and 1000000"));
        }
    }

    private static void overrides(List<MeasureOverride> overrides, LocalDate today, List<Violation> v) {
        if (overrides == null) {
            return;
        }
        if (overrides.size() > MAX_OVERRIDES) {
            v.add(new Violation("overrides", "may list at most " + MAX_OVERRIDES + " overrides"));
        }
        for (int i = 0; i < overrides.size(); i++) {
            String path = "overrides[" + i + "]";
            MeasureOverride o = overrides.get(i);
            if (o == null) {
                v.add(new Violation(path, "must not be null"));
                continue;
            }
            boolean measure = o.measure() != null && !o.measure().isBlank();
            boolean section = o.section() != null && !o.section().isBlank();
            if (measure == section) {
                v.add(new Violation(path + ".measure", "set either measure or section"));
            } else if (measure && HealthDefaults.of(o.measure()) == null) {
                v.add(new Violation(path + ".measure", "is not a known measure"));
            } else if (section && !HealthDefaults.SECTIONS.contains(o.section())) {
                v.add(new Violation(path + ".section", "must be one of " + String.join(", ", HealthDefaults.SECTIONS)));
            }
            if (!MeasureOverride.IGNORED.equals(o.state())) {
                v.add(new Violation(path + ".state", "must be IGNORED"));
            }
            if (o.reason() == null || o.reason().isBlank()) {
                v.add(new Violation(path + ".reason", "is required: say why the measure is ignored"));
            } else if (o.reason().length() > MAX_REASON_LENGTH) {
                v.add(new Violation(path + ".reason", "may be at most " + MAX_REASON_LENGTH + " characters"));
            }
            if (o.until() == null || o.until().isBlank()) {
                v.add(new Violation(path + ".until", "is required: the last day (YYYY-MM-DD) the override applies"));
            } else {
                try {
                    LocalDate until = LocalDate.parse(o.until().trim());
                    if (today != null && until.isAfter(today.plusDays(MAX_OVERRIDE_DAYS))) {
                        v.add(new Violation(path + ".until", "may be at most " + MAX_OVERRIDE_DAYS + " days ahead"));
                    }
                } catch (DateTimeParseException e) {
                    v.add(new Violation(path + ".until", "must be a day YYYY-MM-DD"));
                }
            }
        }
    }
}
