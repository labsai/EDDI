/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.agents.model.HealthConfig;
import ai.labs.eddi.engine.health.model.HealthEventCount;
import ai.labs.eddi.engine.health.report.HealthReports.BreakdownEntry;
import ai.labs.eddi.engine.health.report.HealthReports.Measure;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The business section of Agent Health (H5): one {@code event:<eventType>}
 * measure per event type the agent's current version declares in
 * {@code healthConfig.outcomeEvents}, counting the events of that type matched
 * to the agent's sessions in the period — with a baseline scaled to the
 * period's exposure and a significance test, like the count measures of the
 * adoption section.
 * <p>
 * Attribution is stated, never assumed (plan §5.4): only <em>matched</em>
 * events count — an event no session of the agent set the reference for is not
 * the agent's business — and the breakdown says how each was matched
 * ({@code byReference}, {@code byConversationId}) and sums their values
 * ({@code valueSum}, with {@code n} the events that carried one). An undeclared
 * type is stored and matched, but not shown.
 */
public final class HealthBusinessMeasures {

    /** The prefix of a business measure id. */
    public static final String PREFIX = "event:";

    static final String HINT_NONE = "No event of this type matched a session in this period";
    static final String HINT_UNAVAILABLE = "Business events could not be read";
    static final String HINT_RETENTION = "Business events are kept as long as turn rows (eddi.health.facts-retention)";

    private HealthBusinessMeasures() {
    }

    /**
     * The measures of the declared types.
     *
     * @param declared
     *            event type → outcome or {@link HealthConfig#NO_OUTCOME}, in
     *            declaration order
     * @param current
     *            the period's counts, {@code null} when unavailable
     * @param baseline
     *            the previous period's counts, {@code null} when unavailable
     * @param exposureRatio
     *            the period's exposure over the baseline's
     * @param hint
     *            why there are no counts, when {@code current} is {@code null}
     */
    public static List<Measure> measures(Map<String, String> declared, Collection<HealthEventCount> current, Collection<HealthEventCount> baseline,
                                         double exposureRatio, String hint) {
        List<Measure> measures = new ArrayList<>();
        if (declared == null) {
            return measures;
        }
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            measures.add(measure(entry.getKey(), entry.getValue(), current, baseline, exposureRatio, hint));
        }
        return measures;
    }

    static Measure measure(String type, String outcome, Collection<HealthEventCount> current, Collection<HealthEventCount> baseline,
                           double exposureRatio, String hint) {
        String label = outcome == null || HealthConfig.NO_OUTCOME.equals(outcome) ? type : type + " → " + outcome;
        if (current == null) {
            return new Measure(PREFIX + type, label, HealthDefaults.INFORMATIONAL, false, "count", null, 0, null, null, null, false, "GREY", null,
                    null, hint == null ? HINT_UNAVAILABLE : hint);
        }
        long byReference = 0;
        long byConversation = 0;
        double sum = 0;
        long valued = 0;
        for (HealthEventCount c : current) {
            if (!type.equals(c.eventType())) {
                continue;
            }
            if (HealthOutcomes.EVIDENCE_CONVERSATION_ID_EVENT.equals(c.evidence())) {
                byConversation += c.events();
            } else {
                byReference += c.events();
            }
            sum += c.valueSum();
            valued += c.valued();
        }
        long count = byReference + byConversation;
        Double before = null;
        boolean significant = false;
        if (baseline != null) {
            long previous = baseline.stream().filter(c -> type.equals(c.eventType())).mapToLong(HealthEventCount::events).sum();
            double ratio = exposureRatio > 0 && Double.isFinite(exposureRatio) ? exposureRatio : 1.0;
            before = previous * ratio;
            significant = HealthStatistics.significantCounts(count, previous, ratio);
        }
        Double delta = before == null ? null : count - before;
        List<BreakdownEntry> breakdown = List.of(new BreakdownEntry("byReference", "Matched by reference", (double) byReference, byReference),
                new BreakdownEntry("byConversationId", "Matched by conversation id", (double) byConversation, byConversation),
                new BreakdownEntry("valueSum", "Sum of values", valued > 0 ? sum : null, valued));
        return new Measure(PREFIX + type, label, HealthDefaults.INFORMATIONAL, false, "count", (double) count, count, null, before, delta,
                significant, "GREY", null, breakdown, count == 0 ? HINT_NONE : null);
    }
}
