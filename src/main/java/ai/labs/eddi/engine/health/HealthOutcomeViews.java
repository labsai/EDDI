/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.health.HealthMeasures.OutcomeStats;
import ai.labs.eddi.engine.health.HealthPeriods.Window;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
import ai.labs.eddi.engine.health.report.HealthReports.OutcomeSuggestion;
import ai.labs.eddi.engine.health.report.HealthReports.OutcomeSuggestions;
import ai.labs.eddi.engine.health.report.HealthReports.SessionPage;
import ai.labs.eddi.engine.health.report.HealthReports.SessionRow;
import ai.labs.eddi.engine.hitl.lint.ReservedActionLint;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The two session views of Agent Health: the drill-down into single sessions
 * ({@code GET /agents/{agentId}/health/sessions}) and the outcome-mapping
 * suggestions ({@code GET /agents/{agentId}/health/outcome-suggestions}).
 * Access is the caller's concern ({@code RestAgentHealth}); this service only
 * reads the outcome rows.
 */
@ApplicationScoped
public class HealthOutcomeViews {

    /** The most rows one drill-down page returns. */
    public static final int MAX_PAGE = 100;
    /** The most suggestions returned. */
    static final int MAX_SUGGESTIONS = 20;

    // Name heuristics over whole words (split at _, -, . and camelCase), checked in
    // this order: a "handoff_done" is a handoff first. A word after "not"/"no"/
    // "never" does not count ("not_done"), and "unconfirmed" or "humanize" are not
    // the words matched.
    private static final Pattern WORD_BREAK = Pattern.compile("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])");
    private static final Set<String> NEGATIONS = Set.of("not", "no", "never", "non");
    private static final List<List<String>> DEFLECTED = List.of(List.of("out", "of", "scope"), List.of("outofscope"), List.of("not", "supported"),
            List.of("unsupported"), List.of("off", "topic"));
    private static final Set<String> ESCALATED = Set.of("handoff", "human", "humans", "escalate", "escalated", "escalation", "escalating");
    private static final List<List<String>> ESCALATED_PHRASES = List.of(List.of("hand", "off"), List.of("hand", "over"));
    private static final Set<String> RESOLVED = Set.of("confirm", "confirmed", "confirmation", "done", "complete", "completed", "booked");

    private final IHealthOutcomeStore outcomeStore;
    private final HealthSettings settings;

    @Inject
    public HealthOutcomeViews(IHealthOutcomeStore outcomeStore, HealthSettings settings) {
        this.outcomeStore = outcomeStore;
        this.settings = settings;
    }

    /**
     * One page of sessions, newest end first.
     *
     * @param versions
     *            the versions whose sessions the caller may see — never empty
     * @param offset
     *            from the cursor
     */
    public SessionPage sessions(String agentId, Window window, Set<Integer> versions, List<String> origins, List<String> outcomes,
                                List<String> evidence, int limit, int offset) {
        int size = Math.max(1, Math.min(MAX_PAGE, limit));
        // One more than the page, to know whether there is a next one.
        List<HealthOutcome> found = outcomeStore.sessions(new HealthSessionQuery(agentId, window.from(), window.to(), versions, origins, outcomes,
                evidence, offset, size + 1));
        boolean more = found.size() > size;
        List<SessionRow> items = new ArrayList<>(Math.min(found.size(), size));
        for (HealthOutcome o : found.subList(0, Math.min(found.size(), size))) {
            items.add(new SessionRow(o.conversationId(), o.sessionNo(), o.startedAt() == null ? null : o.startedAt().toString(),
                    o.endedAt() == null ? null : o.endedAt().toString(), o.agentVersion(), o.origin(), o.engaged(), o.outcome(), o.evidence(),
                    o.provisional(), o.turns(), o.costUsd(), o.failures()));
        }
        return new SessionPage(items, more ? String.valueOf(offset + size) : null);
    }

    /**
     * The actions that most often end engaged, closed sessions of the period and
     * are not mapped yet in {@code mapped} (the current version's
     * {@code healthConfig.outcomes}), with an outcome suggested from the name.
     * Never applies anything.
     */
    public OutcomeSuggestions suggestions(String agentId, Window window, Integer version, List<String> origins, Set<String> mapped) {
        HealthUsageQuery query = new HealthUsageQuery(agentId, window.from(), window.to(), version, origins);
        Map<String, Long> ending = outcomeStore.endingActions(query);
        long closed = OutcomeStats.of(outcomeStore.counts(query, false, "none"), settings.minOutcomeCoverage()).closed();
        List<OutcomeSuggestion> items = new ArrayList<>();
        ending.entrySet().stream()
                .filter(e -> e.getKey() != null && !e.getKey().isBlank() && e.getValue() > 0 && !mapped.contains(e.getKey())
                        && !ReservedActionLint.RESERVED_ACTIONS.contains(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_SUGGESTIONS)
                .forEach(e -> {
                    String suggested = suggest(e.getKey());
                    long share = closed > 0 ? Math.round(100.0 * e.getValue() / closed) : 0;
                    String reason = "Last action of " + share + " % of engaged sessions"
                            + (suggested == null ? "; its name suggests no outcome" : "");
                    items.add(new OutcomeSuggestion(e.getKey(), suggested, e.getValue(), reason));
                });
        return new OutcomeSuggestions(items);
    }

    /** The outcome an action's name suggests, or {@code null}. */
    static String suggest(String action) {
        List<String> words = new ArrayList<>();
        for (String word : WORD_BREAK.split(action)) {
            if (!word.isEmpty()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }
        if (DEFLECTED.stream().anyMatch(phrase -> contains(words, phrase))) {
            return HealthOutcomes.DEFLECTED;
        }
        if (matches(words, ESCALATED) || ESCALATED_PHRASES.stream().anyMatch(phrase -> contains(words, phrase))) {
            return HealthOutcomes.ESCALATED;
        }
        if (matches(words, RESOLVED)) {
            return HealthOutcomes.RESOLVED;
        }
        return null;
    }

    /** A word of {@code vocabulary} not negated by the word before it. */
    private static boolean matches(List<String> words, Set<String> vocabulary) {
        for (int i = 0; i < words.size(); i++) {
            if (vocabulary.contains(words.get(i)) && (i == 0 || !NEGATIONS.contains(words.get(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(List<String> words, List<String> phrase) {
        for (int i = 0; i + phrase.size() <= words.size(); i++) {
            if (words.subList(i, i + phrase.size()).equals(phrase)) {
                return true;
            }
        }
        return false;
    }
}
