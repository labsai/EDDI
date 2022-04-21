package ai.labs.eddi.modules.nlp.model;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;


/**
 * @author ginccc
 */
public class Unknown extends Word {
    public Unknown(String value) {
        super(value, new Expressions(new Expression("unknown", new Expression(value))), null);
    }
}
