package ai.labs.eddi.modules.nlp.expressions;

import ai.labs.eddi.modules.nlp.expressions.value.Value;

/**
 * @author ginccc
 */
public class Connector extends Expression {
    public static final Value OR = new Value("or");
    public static final Value AND = new Value("and");

    public Connector() {
    }

    public Connector(Value connectorType) {
        setConnector(connectorType);
    }

    public String getConnector() {
        return expressionName;
    }

    public void setConnector(Expression connector) {
        this.expressionName = connector.toString();
    }

    public void setDomain(String domain) {
        this.domain = domain;
        for (Expression exp : subExpressions)
            exp.setDomain(domain);
    }
}
