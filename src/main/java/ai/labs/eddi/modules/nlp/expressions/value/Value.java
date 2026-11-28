/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.value;

import ai.labs.eddi.modules.nlp.expressions.Expression;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import static ai.labs.eddi.utils.CharacterUtilities.isNumber;
import static ai.labs.eddi.utils.CharacterUtilities.toAsciiDigits;

/**
 * @author ginccc
 */
public class Value extends Expression {
    private static final Pattern BOOLEAN_MATCHER_PATTERN = Pattern.compile("true|false", Pattern.CASE_INSENSITIVE);

    public Value() {
    }

    public Value(String expressionName) {
        // The text as written: a property(...) leaf is kept verbatim, so digits of
        // another script are converted only where a number is compared or converted.
        super(expressionName);
    }

    @Override
    public void setSubExpressions(Expression... subExpressions) {
        log.warn("Tried to set a new SubExpression for a Value Expression!");
        // not implemented
    }

    @Override
    public void addSubExpressions(Expression... subExpressions) {
        log.warn("Tried to add a new SubExpression for a Value Expression!");
        // not implemented
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

    /**
     * The value as an int, or {@code null} when it is not an integer or does not
     * fit an int. Never throws.
     */
    public Integer toInteger() {
        try {
            return Integer.parseInt(toAsciiDigits(expressionName));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * The value as a float, or {@code null} when it is not a number or is out of
     * the float range. Never throws.
     */
    public Float toFloat() {
        try {
            float result = Float.parseFloat(toAsciiDigits(expressionName).replace(',', '.'));
            return Float.isInfinite(result) ? null : result;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Boolean toBoolean() {
        return Boolean.parseBoolean(expressionName);
    }

    /** Numbers longer than this are compared as text instead of parsed. */
    private static final int MAX_PARSED_NUMBER_LENGTH = 100;

    /**
     * The value two numeric expressions are compared by: the exact decimal
     * ({@code 1}, {@code 1.0} and {@code 1,0} are the same, {@code 123456789} and
     * {@code 123456790} are not), or the text itself for an over-long number. Never
     * throws.
     */
    private Object comparableNumber() {
        if (expressionName.length() > MAX_PARSED_NUMBER_LENGTH) {
            return expressionName;
        }
        try {
            return new BigDecimal(toAsciiDigits(expressionName).replace(',', '.')).stripTrailingZeros();
        } catch (NumberFormatException e) {
            return expressionName;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;

        if (o instanceof Value value && expressionName != null && value.expressionName != null && isNumeric() && value.isNumeric()) {
            return comparableNumber().equals(value.comparableNumber());
        }

        return super.equals(o);
    }

    @Override
    public int hashCode() {
        if (expressionName != null && isNumeric()) {
            return comparableNumber().hashCode();
        }
        return super.hashCode();
    }

    private static final Logger log = Logger.getLogger(Value.class);
}
