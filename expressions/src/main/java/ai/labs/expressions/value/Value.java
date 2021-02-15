package ai.labs.expressions.value;

import ai.labs.expressions.Expression;
import ai.labs.utilities.CharacterUtilities;
import lombok.extern.slf4j.Slf4j;

/**
 * @author ginccc
 */
@Slf4j
public class Value extends Expression {

    public Value() {
    }

    public Value(String expressionName) {
        super(expressionName);
    }

    @Override
    public void setSubExpressions(Expression... subExpressions) {
        log.warn("Tried to set a new SubExpression for a Value Expression!");
        //not implemented
    }

    @Override
    public void addSubExpressions(Expression... subExpressions) {
        log.warn("Tried to add a new SubExpression for a Value Expression!");
        //not implemented
    }

    public Boolean isNumeric() {
        return CharacterUtilities.isNumber(expressionName, false);
    }

    public Boolean isDouble() {
        return CharacterUtilities.isNumber(expressionName, true);
    }

    public Integer toInteger() {
        return Integer.parseInt(expressionName);
    }

    public Double toDouble() {
        if (!isNumeric())
            return Double.NaN;
        return Double.parseDouble(expressionName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (isNumeric() && o instanceof Value) {
            Value value = (Value) o;
            return value.toDouble().equals(this.toDouble());
        }

        return super.equals(o);
    }
}
