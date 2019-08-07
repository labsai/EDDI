package ai.labs.expressions;

import ai.labs.expressions.value.AllValue;
import ai.labs.expressions.value.AnyValue;
import ai.labs.expressions.value.Value;
import ai.labs.utilities.CharacterUtilities;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.util.Hashtable;

/**
 * @author ginccc
 */
@Singleton
@Slf4j
public class ExpressionFactory implements IExpressionFactory {
    private Hashtable<String, Expression> expressions = new Hashtable<>();

    public ExpressionFactory() {
        //		expressions.put("", new Parentheses());
        expressions.put("*", new AnyValue());
        expressions.put("all", new AllValue());
        expressions.put("ignored", new Ignored());
        expressions.put("negation", new Negation());
        expressions.put("and", new Connector(Connector.AND));
        expressions.put("or", new Connector(Connector.OR));

    }

    @Override
    public Expression[] getExpression(Expression... expressions) {
        Expressions l = new Expressions();
        for (Expression exp : expressions)
            l.add(getExpression(exp));

        return l.toArray(new Expression[l.size()]);
    }

    @Override
    public Expression getExpression(Expression exp) {
        try {
            if (exp != null) {
                if (expressions.containsKey(exp.getExpressionName())) {
                    Expression tmpExp = exp;
                    exp = (Expression) expressions.get(exp.getExpressionName()).clone();
                    exp.setSubExpressions(tmpExp.getSubExpressions());
                } else {
                    if (exp.getSubExpressions().length == 0 && CharacterUtilities.isNumber(exp.getExpressionName(), false)) {
                        exp = new Value(exp.getExpressionName());
                    } else {
                        exp = new Expression(exp.getExpressionName(), exp.getSubExpressions());
                    }
                }

                exp.setDomain(exp.getDomain());
            }
        } catch (CloneNotSupportedException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        return exp;
    }
}
