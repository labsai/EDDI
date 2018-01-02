package ai.labs.expressions.utilities;

import ai.labs.expressions.Expression;
import ai.labs.expressions.value.Value;

import java.util.List;

public interface IExpressionProvider {
    Expression createExpression(String predicate, Object... values);

    List<Expression> parseExpressions(String expressions);

    Expression parseExpression(String expression);

    void extractAllValues(Expression exp, List<Value> ret);
}
