package ai.labs.eddi.modules.nlp.expressions.value;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import lombok.extern.slf4j.Slf4j;

import java.util.regex.Pattern;

import static ai.labs.eddi.utils.CharacterUtilities.isNumber;

/**
 * @author ginccc
 */
@Slf4j
public class Value extends Expression {
    private static final Pattern BOOLEAN_MATCHER_PATTERN = Pattern.compile("true|false", Pattern.CASE_INSENSITIVE);

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
        return isNumber(expressionName, false);
    }

    public Boolean isDouble() {
        return isNumber(expressionName, true);
    }
    public Boolean isBoolean() {
        return BOOLEAN_MATCHER_PATTERN.matcher(expressionName).matches();
    }

    public Integer toInteger() {
        return Integer.parseInt(expressionName);
    }

    public Float toFloat() {
        return Float.parseFloat(expressionName);
    }

    public Boolean toBoolean() {
        return Boolean.parseBoolean(expressionName);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        if (o instanceof Value && isNumeric()) {
            Value value = (Value) o;
            return value.toFloat().equals(this.toFloat());
        }

        return super.equals(o);
    }
}
