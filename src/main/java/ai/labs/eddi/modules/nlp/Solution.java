/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.expressions.Expressions;

import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */

public class Solution {
    private Expressions expressions;
    private List<String> matchDetails;

    public Solution(Expressions expressions) {
        this(expressions, Collections.emptyList());
    }

    public Solution(Expressions expressions, List<String> matchDetails) {
        this.expressions = expressions;
        this.matchDetails = matchDetails;
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
        return java.util.Objects.equals(expressions, that.expressions);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(expressions);
    }

    @Override
    public String toString() {
        return "Solution(" + "expressions=" + expressions + ")";
    }
}
