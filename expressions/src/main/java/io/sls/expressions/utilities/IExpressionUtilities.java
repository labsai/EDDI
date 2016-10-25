package io.sls.expressions.utilities;

import io.sls.expressions.Expression;
import io.sls.expressions.value.Value;

import java.util.List;

public interface IExpressionUtilities {
    List<Expression> deepCopyExpressions(List<Expression> listReferencesToCopy);

    List<Expression> copyExpressionReferences(List<Expression> listReferencesToCopy);

    Boolean doesListContainOnlyExpressions(List listToCheck);

    List<Expression> insertConnectorBetweenExpressions(List<Expression> exps, Value connectorType);

    Expression createExpression(String predicate, Object... values);

    List<Expression> parseExpressions(String expressions);

    Expression parseExpression(String expression);

    String toString(List<Expression> expressions);

    void extractAllValues(Expression exp, List<Value> ret);
}
