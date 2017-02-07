package ai.labs.expressions;

/**
 * @author ginccc
 */
public interface IExpressionFactory {
    Expression[] getExpression(Expression... expressions);

    Expression getExpression(Expression exp);
}
