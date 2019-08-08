package ai.labs.parser.model;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;

/**
 * @author ginccc
 */
public class Unknown extends Word {
    public Unknown(String value) {
        super(value, new Expressions(new Expression("unknown", new Expression(value))), null);
    }
}
