package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.expressions.Expressions;

/**
 * @author ginccc
 */

public class Solution {
    private Expressions expressions;

    public Solution(Expressions expressions) {
        this.expressions = expressions;
    }

    public Expressions getExpressions() {
        return expressions;
    }

    public void setExpressions(Expressions expressions) {
        this.expressions = expressions;
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
