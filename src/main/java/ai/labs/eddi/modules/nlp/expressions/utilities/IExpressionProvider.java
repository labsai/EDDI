package ai.labs.eddi.modules.nlp.expressions.utilities;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.value.Value;

import java.util.List;

public interface IExpressionProvider {
    Expression createExpression(String predicate, Object... values);

    Expressions parseExpressions(String expressions);

    Expression parseExpression(String expression);

    void extractAllValues(Expression exp, List<Value> ret);
}
