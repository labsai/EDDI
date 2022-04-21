package ai.labs.eddi.modules.nlp.expressions;

import lombok.extern.slf4j.Slf4j;

/**
 * @author ginccc
 */
@Slf4j
public class Negation extends Expression {

    public Negation() {
        super();
        super.expressionName = "negation";
    }

    @Override
    public void setSubExpressions(Expression... subExpressions) {
        if (subExpressions.length > 0)
            super.setSubExpressions(subExpressions[0]);
        if (subExpressions.length > 1) {
            log.warn("Tried to add multiple expressions to Negation! Only first subExpression written.");
        }
    }

    @Override
    public void addSubExpressions(Expression... subExpressions) {
        if (this.subExpressions.size() > 0) {
            log.warn("Overwriting subExpression of Negation!");
        }
        super.addSubExpressions(subExpressions);
        if (subExpressions.length > 1) {
            log.warn("Tried to add multiple expressions to Negation! Only first subExpression written.");
        }
    }

    @Override
    public void setExpressionName(String expressionName) {
        super.expressionName = expressionName;
    }

    public void setCategory(String category) {
        this.domain = category;
        if (getSubExpressions().length > 0)
            getSubExpressions()[0].setDomain(category);
    }
}
