package io.sls.expressions.value;

import io.sls.expressions.Expression;
import io.sls.utilities.CharacterUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * User: jarisch
 * Date: 16.12.2009
 * Time: 11:42:28
 */
public class Value extends Expression {
    private static Logger log = LoggerFactory.getLogger(Value.class);

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
