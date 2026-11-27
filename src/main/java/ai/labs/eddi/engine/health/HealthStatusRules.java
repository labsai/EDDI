/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.model.HealthMarker;
import ai.labs.eddi.engine.health.report.HealthReports.BreakdownEntry;
import ai.labs.eddi.engine.health.report.HealthReports.EffectivenessSection;
import ai.labs.eddi.engine.health.report.HealthReports.Marker;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;
import ai.labs.eddi.engine.health.report.HealthReports.Reason;
import ai.labs.eddi.engine.health.report.HealthReports.Section;
import ai.labs.eddi.engine.health.report.HealthReports.Thresholds;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The status of an agent and the reasons for it (plan §4.1) — pure functions
 * over the measures of a report, so every branch can be checked by a unit test.
 * <ol>
 * <li>Only <b>scored</b> measures count (a budget makes a cost measure scored),
 * and not those an active override ignores. The <b>quality</b> section never
 * counts: who may read ratings differs per caller, and a status that moved with
 * the caller's permissions would leak them.</li>
 * <li>Every counted measure banded {@code GREY} (too little data, or no
 * threshold) → {@code NOT_ENOUGH_DATA}.</li>
 * <li>A <b>critical</b> measure {@code RED} → {@code NEEDS_ATTENTION}.</li>
 * <li>Any measure {@code AMBER} or {@code RED}, or a <b>significant change for
 * the worse</b> of at least {@value #MIN_RATE_EFFECT} (absolute, for rates and
 * scores) or {@value #MIN_RELATIVE_EFFECT} (relative, otherwise) →
 * {@code WATCH}. The effect floor keeps a statistically significant but tiny
 * change of a busy agent from raising a flag.</li>
 * <li>Otherwise {@code HEALTHY}.</li>
 * </ol>
 * <b>Reasons</b> are ordered by severity ({@code RED}, {@code AMBER},
 * {@code INFO}), critical measures first, then by the size of the change, at
 * most {@value #MAX_REASONS}. A resolution rate that rests for more than half
 * on silence always gets the {@code INFO} reason "mostly assumed from silence"
 * (plan §8): an assumed rate must not read as a real one.
 */
public final class HealthStatusRules {

    static final double MIN_RATE_EFFECT = 0.02;
    static final double MIN_RELATIVE_EFFECT = 0.10;
    static final int MAX_REASONS = 5;

    static final String RED = "RED";
    static final String AMBER = "AMBER";
    static final String INFO = "INFO";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final char MINUS = '−';

    /**
     * @param worsening
     *            the largest change for the worse among the counted measures (0 for
     *            none) — the portfolio's second sort key
     */
    public record Verdict(String status, List<Reason> reasons, double worsening) {

        /** Severity of a status, for ordering: needs attention first. */
        public int rank() {
            return rank(status);
        }

        public static int rank(String status) {
            if (status == null) {
                return -1;
            }
            return switch (status) {
                case HealthDefaults.STATUS_NEEDS_ATTENTION -> 3;
                case HealthDefaults.STATUS_WATCH -> 2;
                case HealthDefaults.STATUS_HEALTHY -> 1;
                default -> 0;
            };
        }
    }

    private record Candidate(Reason reason, int severity, boolean critical, double worsening, int order) {
    }

    private HealthStatusRules() {
    }

    /** The measures of every section but quality, in display order. */
    public static List<Measure> statusMeasures(Map<String, Object> sections) {
        List<Measure> out = new ArrayList<>();
        for (Map.Entry<String, Object> entry : sections.entrySet()) {
            if (HealthDefaults.QUALITY.equals(entry.getKey())) {
                continue;
            }
            List<Measure> measures = switch (entry.getValue()) {
                case Section s -> s.restricted() ? List.of() : s.measures();
                case EffectivenessSection e -> e.restricted() ? List.of() : e.measures();
                case null, default -> List.of();
            };
            for (Measure m : measures) {
                if (m != null) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /**
     * Whether the measure got significantly worse by at least the effect floor.
     */
    public static boolean worsened(Measure m) {
        return HealthMeasures.worsening(m) >= minEffect(m);
    }

    static double minEffect(Measure m) {
        return "ratio".equals(m.unit()) || "score".equals(m.unit()) ? MIN_RATE_EFFECT : MIN_RELATIVE_EFFECT;
    }

    /**
     * @param markers
     *            the change markers of the period (oldest first); the newest
     *            deployment or version start explains a change for the worse
     */
    public static Verdict evaluate(Map<String, Object> sections, EffectiveHealthProfile profile, List<Marker> markers) {
        List<Measure> counted = new ArrayList<>();
        for (Measure m : statusMeasures(sections)) {
            if (HealthDefaults.SCORED.equals(m.kind()) && (profile == null || profile.ignored(m.id()) == null)) {
                counted.add(m);
            }
        }
        Marker explanation = explanation(markers);
        List<Candidate> candidates = new ArrayList<>();
        boolean anyBand = false;
        boolean needsAttention = false;
        boolean watch = false;
        double worst = 0;
        for (int i = 0; i < counted.size(); i++) {
            Measure m = counted.get(i);
            String band = m.band();
            if (!"GREY".equals(band)) {
                anyBand = true;
            }
            boolean trend = worsened(m);
            double worsening = trend ? HealthMeasures.worsening(m) : 0;
            worst = Math.max(worst, worsening);
            if ("RED".equals(band)) {
                if (m.critical()) {
                    needsAttention = true;
                } else {
                    watch = true;
                }
                candidates.add(new Candidate(reason(m, RED, trend, explanation), 3, m.critical(), worsening, i));
            } else if ("AMBER".equals(band)) {
                watch = true;
                candidates.add(new Candidate(reason(m, AMBER, trend, explanation), 2, m.critical(), worsening, i));
            } else if (trend) {
                watch = true;
                candidates.add(new Candidate(reason(m, AMBER, true, explanation), 2, m.critical(), worsening, i));
            }
        }
        String status;
        if (!anyBand) {
            status = HealthDefaults.STATUS_NOT_ENOUGH_DATA;
            candidates.clear();
            needsAttention = false;
            watch = false;
            candidates.add(new Candidate(notEnoughData(counted, profile), 1, false, 0, -1));
        } else if (needsAttention) {
            status = HealthDefaults.STATUS_NEEDS_ATTENTION;
        } else if (watch) {
            status = HealthDefaults.STATUS_WATCH;
        } else {
            status = HealthDefaults.STATUS_HEALTHY;
        }
        Reason silence = silenceReason(sections, profile);
        if (silence != null) {
            candidates.add(new Candidate(silence, 1, false, 0, Integer.MAX_VALUE));
        }
        candidates.sort(Comparator.comparingInt(Candidate::severity).reversed().thenComparing(Candidate::critical, Comparator.reverseOrder())
                .thenComparing(Comparator.comparingDouble(Candidate::worsening).reversed()).thenComparingInt(Candidate::order));
        List<Reason> reasons = candidates.stream().limit(MAX_REASONS).map(Candidate::reason).toList();
        return new Verdict(status, reasons, worst);
    }

    /**
     * "Resolution rests mostly on silence" — when the {@code silence} part is more
     * than half of the resolved sessions (and the resolution rate is not ignored).
     */
    static Reason silenceReason(Map<String, Object> sections, EffectiveHealthProfile profile) {
        if (profile != null && profile.ignored("resolutionRate") != null) {
            return null;
        }
        Measure resolution = statusMeasures(sections).stream().filter(m -> "resolutionRate".equals(m.id())).findFirst().orElse(null);
        if (resolution == null || resolution.breakdown() == null) {
            return null;
        }
        long total = 0;
        long silence = 0;
        for (BreakdownEntry part : resolution.breakdown()) {
            total += part.n();
            if ("silence".equals(part.key())) {
                silence += part.n();
            }
        }
        if (total <= 0 || silence * 2 <= total) {
            return null;
        }
        long share = Math.round(100.0 * silence / total);
        return new Reason("resolutionRate", INFO,
                "Resolution rests mostly on silence: " + share + " % of resolved sessions are assumed because people went quiet", null);
    }

    private static Reason notEnoughData(List<Measure> counted, EffectiveHealthProfile profile) {
        for (Measure m : counted) {
            if (m.critical() && m.hint() != null) {
                return new Reason(m.id(), INFO, "Not enough data: " + lowerFirst(m.hint()), null);
            }
        }
        for (Measure m : counted) {
            if (m.hint() != null) {
                return new Reason(m.id(), INFO, "Not enough data: " + lowerFirst(m.hint()), null);
            }
        }
        return new Reason(null, INFO, "Not enough data in this period", null);
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** The newest deployment or version start of the period, or {@code null}. */
    static Marker explanation(List<Marker> markers) {
        if (markers == null) {
            return null;
        }
        Marker found = null;
        for (Marker m : markers) {
            if (HealthMarker.KIND_DEPLOYMENT.equals(m.kind()) || HealthMarker.KIND_VERSION_FIRST_TURN.equals(m.kind())) {
                if (found == null || m.at().compareTo(found.at()) >= 0) {
                    found = m;
                }
            }
        }
        return found;
    }

    /**
     * "Resolution 41 % (−12 pp since v8 on 3 Nov)" for a change for the worse;
     * "Resolution 41 % (target ≥ 70 %)" for a band alone.
     */
    static Reason reason(Measure m, String severity, boolean trend, Marker explanation) {
        StringBuilder text = new StringBuilder(m.label());
        if (m.value() != null) {
            text.append(' ').append(format(m.unit(), m.value()));
        }
        String markerId = null;
        if (trend && m.delta() != null) {
            text.append(" (").append(formatDelta(m.unit(), m.delta())).append(' ');
            if (explanation != null) {
                text.append("since ").append(describe(explanation));
                markerId = explanation.id();
            } else {
                text.append("vs. the previous period");
            }
            text.append(')');
        } else if (m.thresholds() != null && !"GREEN".equals(m.band())) {
            Thresholds t = m.thresholds();
            text.append(" (target ").append(t.higherIsBetter() ? "≥ " : "≤ ").append(format(m.unit(), t.green())).append(')');
        }
        return new Reason(m.id(), severity, text.toString(), markerId);
    }

    private static String describe(Marker marker) {
        String what = marker.version() != null ? "v" + marker.version() : marker.label();
        try {
            LocalDate day = LocalDate.ofInstant(Instant.parse(marker.at()), ZoneOffset.UTC);
            return what + " on " + DAY.format(day);
        } catch (DateTimeParseException e) {
            return what;
        }
    }

    /** A value in its unit, the way a person writes it. */
    static String format(String unit, double value) {
        return switch (unit == null ? "" : unit) {
            case "ratio" -> percent(value * 100) + " %";
            case "score" -> String.format(Locale.ROOT, "%.2f", value);
            case "usd" ->
                Math.abs(value) < 0.01 && value != 0 ? String.format(Locale.ROOT, "$%.4f", value) : String.format(Locale.ROOT, "$%.2f", value);
            case "ms" -> Math.abs(value) >= 1000 ? String.format(Locale.ROOT, "%.1f s", value / 1000) : String.format(Locale.ROOT, "%.0f ms", value);
            default -> String.format(Locale.ROOT, "%,.0f", value);
        };
    }

    /** A change in its unit with its sign: −12 pp, +0.08, +$0.12. */
    static String formatDelta(String unit, double delta) {
        char sign = delta < 0 ? MINUS : '+';
        double abs = Math.abs(delta);
        return switch (unit == null ? "" : unit) {
            case "ratio" -> sign + percent(abs * 100) + " pp";
            default -> sign + format(unit, abs);
        };
    }

    private static String percent(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        if (Math.abs(rounded) >= 10 || rounded == Math.rint(rounded)) {
            return String.valueOf(Math.round(value));
        }
        return String.format(Locale.ROOT, "%.1f", rounded);
    }
}
