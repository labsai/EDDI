package ai.labs.parser.model;

import ai.labs.expressions.Expression;

import java.util.Collections;

/**
 * @author ginccc
 */
public class Unknown extends Word {
    public Unknown(String value) {
        super(value, Collections.singletonList(new Expression("unknown", new Expression(value))), null);
    }
}
