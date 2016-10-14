package io.sls.expressions;

import io.sls.expressions.value.Value;


/**
 * Created by IntelliJ IDEA.
 * User: jarisch
 * Date: 10.12.2009
 * Time: 16:34:56
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
