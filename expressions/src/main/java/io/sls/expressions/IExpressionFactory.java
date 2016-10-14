package io.sls.expressions;

/**
 * Created by jariscgr on 09.08.2016.
 */
public interface IExpressionFactory {
    Expression[] getExpression(Expression... expressions);

    Expression getExpression(Expression exp);
}
