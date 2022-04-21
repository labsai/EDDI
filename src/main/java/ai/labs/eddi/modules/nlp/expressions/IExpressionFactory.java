package ai.labs.eddi.modules.nlp.expressions;

/**
 * @author ginccc
 */
public interface IExpressionFactory {
    Expression[] getExpression(Expression... expressions);

    Expression getExpression(Expression exp);
}
