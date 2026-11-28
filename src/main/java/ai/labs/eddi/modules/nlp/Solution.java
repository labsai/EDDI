/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.expressions.Expressions;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * @author ginccc
 */

public class Solution {
    private Expressions expressions;
    private List<String> matchDetails;
    private Double confidence;
    private boolean corrected;

    public Solution(Expressions expressions) {
        this(expressions, Collections.emptyList());
    }

    public Solution(Expressions expressions, List<String> matchDetails) {
        this(expressions, matchDetails, null, false);
    }

    /**
     * @param confidence
     *            accuracy of the least accurate match that contributed an
     *            expression, on the 0..1 scale; {@code null} when no match
     *            contributed one (only unknown or unused words)
     * @param corrected
     *            whether any contributing match came from a correction
     */
    public Solution(Expressions expressions, List<String> matchDetails, Double confidence, boolean corrected) {
        this.expressions = expressions;
        this.matchDetails = matchDetails;
        this.confidence = confidence;
        this.corrected = corrected;
    }

    public Double getConfidence() {
        return confidence;
    }

    public boolean isCorrected() {
        return corrected;
    }

    /**
     * Whether the dictionaries understood anything: at least one expression other
     * than {@code unknown} and {@code unused}.
     */
    public boolean hasMeaningfulExpressions() {
        return expressions != null && expressions.stream().anyMatch(DictionaryUtilities::isMeaningful);
    }

    public Expressions getExpressions() {
        return expressions;
    }

    public void setExpressions(Expressions expressions) {
        this.expressions = expressions;
    }

    public List<String> getMatchDetails() {
        return matchDetails;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Solution that = (Solution) o;
        return Objects.equals(expressions, that.expressions);
    }

    @Override
    public int hashCode() {
        return Objects.hash(expressions);
    }

    @Override
    public String toString() {
        return "Solution(" + "expressions=" + expressions + ")";
    }
}
