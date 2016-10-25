package io.sls.expressions;

/**
 * @author ginccc
 */
public interface IExpressionFactory {
    Expression[] getExpression(Expression... expressions);

    Expression getExpression(Expression exp);
}
