/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.Limits;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.nlp.semantic.SemanticFallback;

import java.util.List;
import java.util.Objects;

/**
 * @author ginccc
 */
public interface IInputParser {
    Config getConfig();

    /** The parser's safety bounds; the shipped defaults unless configured. */
    default Limits getLimits() {
        return Limits.DEFAULT;
    }

    /**
     * The embedding-based fallback consulted when the dictionaries understood
     * nothing, or {@code null} when none is configured.
     */
    default SemanticFallback getSemanticFallback() {
        return null;
    }

    /**
     * Whether the parser has nothing to understand text with: no normalizers,
     * dictionaries, corrections or semantic fallback, and
     * {@link Config#getPassThrough()} does not switch it off. Such a parser only
     * has to produce one {@code unknown} expression so that a catch-all rule
     * matches, and {@code InputParserTask} skips tokenising for it.
     */
    default boolean isPassThrough() {
        return false;
    }

    /**
     * Whether the conversation output {@code input} — the copy the model, the
     * conversation log and {@code {memory.current.input}} read — keeps the user's
     * text as typed instead of being replaced with the normalised text. On when the
     * parser has no normalizers, unless {@link Config#getPreserveInput()} says
     * otherwise.
     */
    default boolean preservesInput() {
        return false;
    }

    String normalize(String sentence, String userLanguage) throws InterruptedException;

    List<RawSolution> parse(String sentence) throws InterruptedException;

    List<RawSolution> parse(String sentence, String userLanguage, List<IDictionary> temporaryDictionaries) throws InterruptedException;

    /**
     * Every reading of the sentence up to the parser's solution limit, for callers
     * that list them (the {@code /parser} endpoint). {@link #parse} may stop early
     * once it has the reading the pipeline uses and the configured alternatives.
     */
    default List<RawSolution> parseAllReadings(String sentence, String userLanguage) throws InterruptedException {
        return parse(sentence, userLanguage, List.of());
    }

    class Config {
        public static final double DEFAULT_MIN_PARTIAL_PHRASE_COVERAGE = 0.5;

        private boolean appendExpressions = true;
        private boolean includeUnused = true;
        private boolean includeUnknown = true;
        /**
         * Whether a fragment of a phrase — "good morning" of "good morning sir" —
         * yields the phrase's expressions. Off by default: it lets fewer words trigger
         * a phrase than the agent designer wrote.
         */
        private boolean matchPartialPhrases = false;
        /**
         * Smallest share of a phrase's words a fragment must cover to count, when
         * {@link #matchPartialPhrases} is on.
         */
        private double minPartialPhraseCoverage = DEFAULT_MIN_PARTIAL_PHRASE_COVERAGE;
        /**
         * Matches less accurate than this contribute no expressions. {@code 0} keeps
         * every match; {@code 1} keeps only exact ones.
         */
        private double minMatchingAccuracy = 0.0;
        /**
         * How many alternative readings, besides the chosen one, are stored as
         * {@code expressions:alternatives}. {@code 0} stores none.
         */
        private int maxAlternatives = 0;
        /**
         * {@code null}: automatic — keep the user's text as typed in the conversation
         * output {@code input} when the parser has no normalizers. {@code true}: always
         * keep it. {@code false}: always replace it with the normalised text.
         */
        private Boolean preserveInput;
        /**
         * {@code null}: automatic — a parser without normalizers, dictionaries,
         * corrections and semantic fallback yields a single {@code unknown} expression
         * for the whole text. {@code false}: one {@code unknown} per word as before.
         */
        private Boolean passThrough;

        public Config() {
        }

        public Config(boolean appendExpressions, boolean includeUnused, boolean includeUnknown) {
            this.appendExpressions = appendExpressions;
            this.includeUnused = includeUnused;
            this.includeUnknown = includeUnknown;
        }

        public boolean isAppendExpressions() {
            return appendExpressions;
        }

        public void setAppendExpressions(boolean appendExpressions) {
            this.appendExpressions = appendExpressions;
        }

        public boolean isIncludeUnused() {
            return includeUnused;
        }

        public void setIncludeUnused(boolean includeUnused) {
            this.includeUnused = includeUnused;
        }

        public boolean isIncludeUnknown() {
            return includeUnknown;
        }

        public void setIncludeUnknown(boolean includeUnknown) {
            this.includeUnknown = includeUnknown;
        }

        public boolean isMatchPartialPhrases() {
            return matchPartialPhrases;
        }

        public void setMatchPartialPhrases(boolean matchPartialPhrases) {
            this.matchPartialPhrases = matchPartialPhrases;
        }

        public double getMinPartialPhraseCoverage() {
            return minPartialPhraseCoverage;
        }

        public void setMinPartialPhraseCoverage(double minPartialPhraseCoverage) {
            this.minPartialPhraseCoverage = minPartialPhraseCoverage;
        }

        public double getMinMatchingAccuracy() {
            return minMatchingAccuracy;
        }

        public void setMinMatchingAccuracy(double minMatchingAccuracy) {
            this.minMatchingAccuracy = minMatchingAccuracy;
        }

        public int getMaxAlternatives() {
            return maxAlternatives;
        }

        public Boolean getPreserveInput() {
            return preserveInput;
        }

        public void setPreserveInput(Boolean preserveInput) {
            this.preserveInput = preserveInput;
        }

        public Boolean getPassThrough() {
            return passThrough;
        }

        public void setPassThrough(Boolean passThrough) {
            this.passThrough = passThrough;
        }

        public void setMaxAlternatives(int maxAlternatives) {
            this.maxAlternatives = maxAlternatives;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            Config that = (Config) o;
            return appendExpressions == that.appendExpressions && includeUnused == that.includeUnused && includeUnknown == that.includeUnknown
                    && matchPartialPhrases == that.matchPartialPhrases
                    && Double.compare(minPartialPhraseCoverage, that.minPartialPhraseCoverage) == 0
                    && Double.compare(minMatchingAccuracy, that.minMatchingAccuracy) == 0 && maxAlternatives == that.maxAlternatives
                    && Objects.equals(preserveInput, that.preserveInput) && Objects.equals(passThrough, that.passThrough);
        }

        @Override
        public int hashCode() {
            return Objects.hash(appendExpressions, includeUnused, includeUnknown, matchPartialPhrases, minPartialPhraseCoverage, minMatchingAccuracy,
                    maxAlternatives, preserveInput, passThrough);
        }

        @Override
        public String toString() {
            return "Config(" + "appendExpressions=" + appendExpressions + ", includeUnused=" + includeUnused + ", includeUnknown=" + includeUnknown
                    + ", matchPartialPhrases=" + matchPartialPhrases + ", minPartialPhraseCoverage=" + minPartialPhraseCoverage
                    + ", minMatchingAccuracy=" + minMatchingAccuracy + ", maxAlternatives=" + maxAlternatives + ")";
        }
    }
}
