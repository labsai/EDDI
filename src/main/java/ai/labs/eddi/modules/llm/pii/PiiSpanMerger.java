/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.pii;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Turns the spans of one or more detectors into a list that can be replaced
 * left to right: sorted by start, no two overlapping.
 * <p>
 * Rules, in this order:
 * <ol>
 * <li>A span inside a {@code skip} range is dropped — the ranges of text that
 * must not be touched again (placeholders EDDI already wrote).</li>
 * <li>A span inside an occurrence of a {@code neverTerms} entry is dropped —
 * the author's allow-list ("Musterkanzlei GmbH").</li>
 * <li>Overlaps are resolved greedily by priority: a span from the author's own
 * term list ({@link #TERMS_DETECTOR_ID}) first, then the longer span, then the
 * higher score, then the span reported earlier (pass the detectors' spans in
 * chain order).</li>
 * </ol>
 */
public final class PiiSpanMerger {

    /**
     * The {@link PiiSpan#detectorId()} of spans from the author's always-replace
     * term list; they win every overlap.
     */
    public static final String TERMS_DETECTOR_ID = "terms";

    /**
     * @param neverTerms
     *            values that are never personal data here (whole-word,
     *            case-insensitive)
     * @param skip
     *            ranges no span may overlap
     */
    public record Rules(Collection<String> neverTerms, List<TextRange> skip) {

        public static final Rules NONE = new Rules(List.of(), List.of());

        public Rules {
            neverTerms = neverTerms == null ? List.of() : List.copyOf(neverTerms);
            skip = skip == null ? List.of() : List.copyOf(skip);
        }
    }

    private PiiSpanMerger() {
    }

    /**
     * The spans to act on, sorted by start and free of overlaps. Spans touching a
     * skip range or lying inside a never-term are dropped; of overlapping spans the
     * higher-priority one wins (always-terms, then length, then score, then input
     * order).
     */
    public static List<PiiSpan> merge(String text, List<PiiSpan> spans, Rules rules) {
        List<TextRange> never = TermMatcher.occurrences(text, rules.neverTerms());
        List<Ranked> candidates = new ArrayList<>();
        for (int i = 0; i < spans.size(); i++) {
            PiiSpan span = spans.get(i);
            if (span.end() > text.length() || overlapsAny(span, rules.skip()) || insideAny(span, never)) {
                continue;
            }
            candidates.add(new Ranked(span, i));
        }
        candidates.sort(PRIORITY);
        List<PiiSpan> accepted = new ArrayList<>();
        for (Ranked candidate : candidates) {
            if (accepted.stream().noneMatch(a -> a.overlaps(candidate.span().start(), candidate.span().end()))) {
                accepted.add(candidate.span());
            }
        }
        accepted.sort(Comparator.comparingInt(PiiSpan::start));
        return accepted;
    }

    private record Ranked(PiiSpan span, int order) {
    }

    private static final Comparator<Ranked> PRIORITY = Comparator
            .comparing((Ranked r) -> !TERMS_DETECTOR_ID.equals(r.span().detectorId()))
            .thenComparing(r -> -r.span().length())
            .thenComparing(r -> -r.span().score())
            .thenComparingInt(Ranked::order);

    /** Whether {@code span} shares a character with any of {@code ranges}. */
    private static boolean overlapsAny(PiiSpan span, List<TextRange> ranges) {
        for (TextRange range : ranges) {
            if (span.overlaps(range.start(), range.end())) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code span} lies wholly inside one of {@code ranges}. */
    private static boolean insideAny(PiiSpan span, List<TextRange> ranges) {
        for (TextRange range : ranges) {
            if (range.contains(span.start(), span.end())) {
                return true;
            }
        }
        return false;
    }
}
