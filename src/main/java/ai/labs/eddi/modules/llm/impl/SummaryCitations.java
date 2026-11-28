/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import io.micrometer.core.instrument.MeterRegistry;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic checks for traceable, extractive summaries: the rolling
 * conversation summary ({@code conversationSummary.citeSources}) and Dream's
 * memory consolidation ({@code dream.citeSources}). No model is involved — a
 * summary either backs every number with the source it cites, or it does not.
 * <p>
 * <strong>Citations.</strong> A citation is a bracketed list of conversation
 * steps: {@code [turn 5]}, {@code [turns 4-7]}, {@code [turns 4-7, 9]} —
 * {@code step}/{@code steps} are accepted as synonyms, ranges are inclusive and
 * may use {@code -}, an en or em dash or {@code to}. The numbers are
 * conversation step indices, exactly as the summarizer input labels them and
 * {@code ConversationRecallTool} reads them: 0-based, step 0 is the opening
 * ({@code CONVERSATION_START}) step, step 1 the user's first message.
 * <p>
 * <strong>Numbers.</strong> A number is a maximal run of ASCII digits that may
 * contain single {@code ,} or {@code .} separators between digit groups
 * ({@code 1,000}, {@code 1.000}, {@code 3.14}, {@code 1,000.50}), optionally
 * preceded by a {@code -} that does not follow a letter or digit ({@code -5},
 * but the {@code 7} of {@code 4-7} and the {@code 1234} of {@code A-1234}), and
 * optionally followed by {@code %}. Two numbers match only when their text is
 * identical — {@code 1,000} does not match {@code 1.000} or {@code 1000},
 * {@code 10%} does not match {@code 10}, {@code -5} does not match {@code 5}.
 * Excluded from the check: the step indices inside citations, unbracketed step
 * references ({@code turn 5}, {@code steps 3-4} — a reference to a turn, not a
 * fact from it), and a numbered-list marker at the start of a line
 * ({@code 1. }, {@code 2) }).
 *
 * @since 6.7.0
 */
public final class SummaryCitations {

    private static final Logger LOGGER = Logger.getLogger(SummaryCitations.class);

    /**
     * What a summarizer does with a summary that fails the check. Parsed leniently
     * from config text: {@code null}, blank or unknown is the caller's default.
     */
    public enum OnValidationFailure {
        /** Store the summary anyway (counted and logged). */
        keep,
        /**
         * Ask the summarizer once more, with the problems listed; if the second answer
         * fails too (or the retry is refused by the spend check), fall back.
         */
        retry_once,
        /** Discard the new summary and keep what was there before. */
        fallback_to_previous;

        /**
         * {@code value} as a policy; {@code defaultPolicy} when it is null or blank,
         * and — with a WARN — when it names no policy.
         */
        public static OnValidationFailure parse(String value, OnValidationFailure defaultPolicy) {
            if (value == null || value.isBlank()) {
                return defaultPolicy;
            }
            try {
                return valueOf(value.trim().toLowerCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                LOGGER.warnf("[SUMMARY] Unknown onValidationFailure '%s' — using '%s'. Valid: keep, retry_once, fallback_to_previous.",
                        value.length() > 40 ? value.substring(0, 40) : value, defaultPolicy);
                return defaultPolicy;
            }
        }
    }

    /**
     * Outcomes counted on {@code eddi.summary.citation.checks{source,outcome}}.
     * {@code passed}: the first answer passed. {@code retried}: the second answer
     * passed. {@code kept}: failed and stored anyway. {@code fallback}: failed and
     * discarded.
     */
    public static final String OUTCOME_PASSED = "passed";
    public static final String OUTCOME_RETRIED = "retried";
    public static final String OUTCOME_KEPT = "kept";
    public static final String OUTCOME_FALLBACK = "fallback";

    /** {@code source} tag of the rolling conversation summary. */
    public static final String SOURCE_CONVERSATION = "conversation";

    /** {@code source} tag of Dream's memory consolidation. */
    public static final String SOURCE_DREAM = "dream";

    /**
     * Counts one checked summary on
     * {@code eddi.summary.citation.checks{source,outcome}}; a null registry counts
     * nothing.
     */
    public static void count(MeterRegistry meterRegistry, String source, String outcome) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.summary.citation.checks", "source", source, "outcome", outcome).increment();
        }
    }

    /** Most problems listed in a log line or in the retry's feedback. */
    static final int MAX_LISTED_PROBLEMS = 10;

    /**
     * The content of a bracketed citation is read only up to this length — a
     * citation is a few characters; a longer bracket is not one.
     */
    private static final int MAX_CITATION_BODY = 200;

    /** Largest step index read from a citation; larger saturates. */
    private static final int MAX_INDEX_DIGITS = 9;

    private static final Pattern CITATION = Pattern.compile("\\[\\s*(?:turns?|steps?)\\s+([^\\[\\]\\n]{1," + MAX_CITATION_BODY + "})\\]",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CITATION_PART = Pattern
            .compile("\\s*(\\d{1," + MAX_INDEX_DIGITS + "})\\s*(?:(?:-|–|—|to)\\s*(\\d{1," + MAX_INDEX_DIGITS + "}))?\\s*",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern CITATION_SEPARATOR = Pattern.compile(",|;|\\band\\b", Pattern.CASE_INSENSITIVE);

    /** An unbracketed reference to a step: {@code turn 5}, {@code steps 3-4}. */
    private static final Pattern STEP_REFERENCE = Pattern.compile(
            "\\b(?:turns?|steps?)\\s+\\d{1," + MAX_INDEX_DIGITS + "}(?:\\s*(?:-|–|—|to)\\s*\\d{1," + MAX_INDEX_DIGITS + "})?\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern NUMBER = Pattern.compile("(?:(?<![\\p{L}\\p{N}])-)?\\d+(?:[.,]\\d+)*%?");

    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*\\d{1,3}[.)]\\s+");

    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•+]|\\d{1,3}[.)])\\s+\\S");

    private SummaryCitations() {
    }

    /** An inclusive range of step indices. */
    public record Range(int from, int to) {
        @Override
        public String toString() {
            return from == to ? String.valueOf(from) : from + "-" + to;
        }
    }

    /** The outcome of a check; valid when it lists no problem. */
    public record Result(List<String> problems) {

        public Result {
            problems = List.copyOf(problems);
        }

        public boolean valid() {
            return problems.isEmpty();
        }

        /** At most {@link #MAX_LISTED_PROBLEMS} problems, one per line. */
        public String describe() {
            var sb = new StringBuilder();
            int shown = Math.min(problems.size(), MAX_LISTED_PROBLEMS);
            for (int i = 0; i < shown; i++) {
                sb.append("- ").append(problems.get(i)).append('\n');
            }
            if (problems.size() > shown) {
                sb.append("- … and ").append(problems.size() - shown).append(" more\n");
            }
            return sb.toString();
        }
    }

    /**
     * Instructions appended to the rolling-summary prompt (built-in or custom) when
     * {@code citeSources} is on.
     */
    public static final String CONVERSATION_CITATION_INSTRUCTIONS = """
            Cite your sources. End every bullet point with the turn numbers it is based on, in square brackets,
            using the turn numbers exactly as the input labels them: [turn 5], [turns 4-7] or [turns 4-7, 9].
            A point carried over from the previous summary keeps the turn numbers it cites there; if it cites none,
            cite the whole range of the previous summary, as given in its heading.
            Copy every number exactly as it appears in the turns you cite: the same digits, separators, sign and
            percent sign. Do not round, convert, add up or otherwise compute numbers.
            When the turns are ambiguous or do not say, write "unclear in source" instead of guessing.""";

    /** Feedback appended to the instructions of a retry. */
    public static String retryFeedback(Result result) {
        return "\n\nYour previous answer failed the citation check:\n" + result.describe()
                + "Write the summary again. Every point must cite its turns, and every number must appear exactly as written "
                + "in the turns it cites.";
    }

    /**
     * Every bracketed citation in {@code text}, in order. A citation whose content
     * is not a list of step numbers and ranges is reported as a problem through
     * {@code malformed} (null to ignore).
     */
    public static List<Range> citations(String text, List<String> malformed) {
        List<Range> ranges = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return ranges;
        }
        Matcher matcher = CITATION.matcher(text);
        while (matcher.find()) {
            String body = matcher.group(1);
            boolean ok = true;
            List<Range> parsed = new ArrayList<>();
            for (String part : CITATION_SEPARATOR.split(body, -1)) {
                Matcher pm = CITATION_PART.matcher(part);
                if (!pm.matches()) {
                    ok = false;
                    break;
                }
                int first = parseIndex(pm.group(1));
                int last = pm.group(2) != null ? parseIndex(pm.group(2)) : first;
                parsed.add(first <= last ? new Range(first, last) : new Range(last, first));
            }
            if (ok) {
                ranges.addAll(parsed);
            } else if (malformed != null) {
                malformed.add("citation '" + matcher.group() + "' is not a list of turn numbers and ranges");
            }
        }
        return ranges;
    }

    /**
     * {@code text} without bracketed citations, unbracketed step references and
     * numbered-list markers — what the number check reads.
     */
    static String withoutStepReferences(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String withoutCitations = CITATION.matcher(text).replaceAll(" ");
        String withoutReferences = STEP_REFERENCE.matcher(withoutCitations).replaceAll(" ");
        var sb = new StringBuilder(withoutReferences.length());
        for (String line : withoutReferences.split("\n", -1)) {
            sb.append(LIST_MARKER.matcher(line).replaceFirst("")).append('\n');
        }
        return sb.toString();
    }

    /** The numbers in {@code text}, in order of first appearance. */
    public static Set<String> numbers(String text) {
        Set<String> found = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return found;
        }
        Matcher matcher = NUMBER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }

    /**
     * The numbers of a summary's own text: as {@link #numbers}, after
     * {@link #withoutStepReferences}.
     */
    public static Set<String> factNumbers(String text) {
        return numbers(withoutStepReferences(text));
    }

    /**
     * Checks a rolling conversation summary.
     *
     * @param summary
     *            the summarizer's answer
     * @param previousThroughStep
     *            the step the previous summary covers through (exclusive): the
     *            first step of the new batch
     * @param throughStep
     *            the step the new summary covers through (exclusive)
     * @param stepTexts
     *            the text of each step of the new batch, as the summarizer saw it
     *            (user input and agent output, after the input screen)
     * @param previousSummary
     *            the previous summary the request carried, or null when there was
     *            none. A citation of a step before {@code previousThroughStep} is a
     *            carried-over point: in range only when there is a previous
     *            summary, and its numbers are checked against that summary's text,
     *            the only source of those steps the summarizer saw
     */
    public static Result checkConversationSummary(String summary, int previousThroughStep, int throughStep, Map<Integer, String> stepTexts,
                                                  String previousSummary) {
        List<String> problems = new ArrayList<>();
        if (summary == null || summary.isBlank()) {
            problems.add("the summary is empty");
            return new Result(problems);
        }
        boolean hasPrevious = previousSummary != null && !previousSummary.isBlank();
        int lowest = hasPrevious ? 0 : previousThroughStep;
        Set<String> previousNumbers = hasPrevious ? factNumbers(previousSummary) : Set.of();

        for (Unit unit : units(summary)) {
            List<Range> cited = citations(unit.text, problems);
            if (unit.point && cited.isEmpty()) {
                problems.add("no citation for: " + abbreviate(unit.text));
            }
            Set<String> backing = new HashSet<>();
            boolean citesPrevious = false;
            for (Range range : cited) {
                if (range.from() < lowest || range.to() >= throughStep) {
                    problems.add("[turns " + range + "] is outside the summarized turns " + lowest + "-" + (throughStep - 1));
                    continue;
                }
                for (int step = range.from(); step <= range.to(); step++) {
                    if (step < previousThroughStep) {
                        citesPrevious = true;
                    } else {
                        backing.addAll(numbers(stepTexts.get(step)));
                    }
                }
            }
            if (citesPrevious) {
                backing.addAll(previousNumbers);
            }
            for (String number : factNumbers(unit.text)) {
                if (!backing.contains(number)) {
                    problems.add(cited.isEmpty()
                            ? "number " + number + " has no citation"
                            : "number " + number + " does not appear in the cited turns " + joinRanges(cited));
                }
            }
        }
        return new Result(problems);
    }

    /**
     * Checks one consolidated memory: it must cite at least one source, every cited
     * source must exist, and every number of {@code value} must appear in a cited
     * source's text.
     *
     * @param sourceRefs
     *            the refs the consolidated entry cites
     * @param sourceTexts
     *            the text of each source the model was given, by ref
     *            ({@code 0..n-1})
     */
    public static Result checkConsolidatedEntry(String key, String value, Collection<Integer> sourceRefs, List<String> sourceTexts) {
        List<String> problems = new ArrayList<>();
        String label = "entry '" + abbreviate(key) + "'";
        if (sourceRefs == null || sourceRefs.isEmpty()) {
            problems.add(label + " cites no source");
        }
        Set<String> backing = new HashSet<>();
        if (sourceRefs != null) {
            for (Integer ref : sourceRefs) {
                if (ref == null || ref < 0 || ref >= sourceTexts.size()) {
                    problems.add(label + " cites source " + ref + ", which does not exist (0-" + (sourceTexts.size() - 1) + ")");
                    continue;
                }
                backing.addAll(numbers(sourceTexts.get(ref)));
            }
        }
        for (String number : numbers(value)) {
            if (!backing.contains(number)) {
                problems.add(label + ": number " + number + " does not appear in its cited sources");
            }
        }
        return new Result(problems);
    }

    private static String joinRanges(List<Range> ranges) {
        var sb = new StringBuilder();
        for (Range range : ranges) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(range);
        }
        return sb.toString();
    }

    private static int parseIndex(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return flat.length() > 80 ? flat.substring(0, 80) + "…" : flat;
    }

    /**
     * A unit of a summary checked on its own: a bullet point with its continuation
     * lines, or a standalone line.
     */
    private record Unit(String text, boolean point) {
    }

    /**
     * Splits a summary into units. A bullet ({@code -}, {@code *}, {@code •},
     * {@code +}, {@code 1.}, {@code 1)}) starts a point; following lines that are
     * neither blank, a heading ({@code #…}) nor another bullet continue it. Any
     * other non-blank line is a unit of its own, and not a point: it needs a
     * citation only for the numbers it states.
     */
    private static List<Unit> units(String summary) {
        List<Unit> units = new ArrayList<>();
        StringBuilder point = null;
        for (String line : summary.split("\n", -1)) {
            boolean blank = line.isBlank();
            boolean heading = line.stripLeading().startsWith("#");
            if (!blank && !heading && BULLET.matcher(line).find()) {
                if (point != null) {
                    units.add(new Unit(point.toString(), true));
                }
                point = new StringBuilder(line);
                continue;
            }
            if (point != null && !blank && !heading) {
                point.append('\n').append(line);
                continue;
            }
            if (point != null) {
                units.add(new Unit(point.toString(), true));
                point = null;
            }
            if (!blank) {
                units.add(new Unit(line, false));
            }
        }
        if (point != null) {
            units.add(new Unit(point.toString(), true));
        }
        return units;
    }
}
