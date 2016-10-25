package io.sls.expressions.utilities;

import io.sls.expressions.Connector;
import io.sls.expressions.Expression;
import io.sls.expressions.IExpressionFactory;
import io.sls.expressions.value.Value;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * User: Alex
 * Date: 20.11.2010
 * Time: 13:51:15
 */

@Singleton
@Slf4j
public class ExpressionUtilities implements IExpressionUtilities {
    private final IExpressionFactory expressionFactory;

    @Inject
    public ExpressionUtilities(IExpressionFactory expressionFactory) {
        this.expressionFactory = expressionFactory;
    }

    @Override
    public List<Expression> deepCopyExpressions(List<Expression> listReferencesToCopy) {
        List<Expression> listCopy = new ArrayList<>();
        for (Expression expToCopy : listReferencesToCopy) {
            try {
                listCopy.add((Expression) expToCopy.clone());
            } catch (CloneNotSupportedException e) {
                log.error("Clone failed!", e);
            }
        }
        return listCopy;
    }

    @Override
    public List<Expression> copyExpressionReferences(List<Expression> listReferencesToCopy) {
        List<Expression> listCopy = new ArrayList<>();
        listCopy.addAll(listReferencesToCopy);
        return listCopy;
    }

    @Override
    public Boolean doesListContainOnlyExpressions(List listToCheck) {
        for (Object o : listToCheck) {
            if (!(o instanceof Expression)) return false;
        }
        return true;
    }

    /**
     * Inserts the given operator type between the expressions in the given list.
     * Also considers connectors that might already exist in the list,
     * so that there are no adjacent connectors.
     */
    @Override
    public List<Expression> insertConnectorBetweenExpressions(List<Expression> exps, Value connectorType) {
        List<Expression> result = new ArrayList<>();
        if (exps.isEmpty()) return result;
        result.add(exps.get(0));
        boolean lastExpWasConnector = exps.get(0) instanceof Connector;
        boolean nextExpIsConnector = (exps.size() > 1 && exps.get(1) instanceof Connector);
        for (int i = 1; i < exps.size(); i++) {
            if (!lastExpWasConnector && !nextExpIsConnector) {
                Connector conn = new Connector();
                conn.setConnector(connectorType);
                result.add(conn);
            }
            result.add(exps.get(i));
            lastExpWasConnector = exps.get(i) instanceof Connector;
            nextExpIsConnector = (exps.size() > i && exps.get(i) instanceof Connector);
        }
        return result;
    }


    @Override
    public Expression createExpression(String predicate, Object... values) {
        StringBuilder sb = new StringBuilder(predicate);
        if (values != null && values.length > 0) {
            sb.append("(");

            for (Object value : values)
                sb.append(value).append(",");

            sb.deleteCharAt(sb.length() - 1);
            sb.append(")");
        }

        return parseExpression(sb.toString());
    }

    @Override
    public List<Expression> parseExpressions(String expressions) {
        expressions = expressions.trim();

        LinkedList<String> listStringExps = new LinkedList<>();
        LinkedList<Expression> listExpression = new LinkedList<>();

        int lastPos = 0;
        int parenthesis = -1;
        for (int i = 0; i < expressions.length(); i++) {
            if (expressions.charAt(i) == ',' && i != 0 && expressions.charAt(i - 1) != ')' && parenthesis < 1) {
                listStringExps.add(expressions.substring(lastPos, i));
                lastPos = i + 1;
            }

            if (expressions.charAt(i) == '(') {
                if (parenthesis == -1)
                    parenthesis = 0;
                parenthesis++;
            }

            if (expressions.charAt(i) == ')')
                parenthesis--;

            if (parenthesis == 0) {
                listStringExps.add(expressions.substring(lastPos, i + 1));
                parenthesis = -1;
                lastPos = i + 2;
            }
        }

        if (lastPos < expressions.length())
            listStringExps.add(expressions.substring(lastPos, expressions.length()));

        listExpression.addAll(listStringExps.stream().map(this::parseExpression).collect(Collectors.toList()));


        return listExpression;

    }

    @Override
    public Expression parseExpression(String expression) {
        expression = expression.trim();
        Expression exp;
        int indexOfOpening = expression.indexOf("(");
        int indexOfClosing = expression.lastIndexOf(")");
        if (indexOfOpening > -1 && indexOfClosing > -1) {
            String tmp = expression.substring(0, indexOfOpening);
            exp = new Expression(tmp.trim());
            String subExpressions = expression.substring(indexOfOpening + 1, indexOfClosing);
            List<Expression> expressions = parseExpressions(subExpressions);
            exp.setSubExpressions(expressions);
        } else {
            exp = new Expression(expression);
        }

        return expressionFactory.getExpression(exp);
    }

    @Override
    public String toString(List<Expression> expressions) {
        StringBuilder ret = new StringBuilder();
        for (Expression expression : expressions) {
            ret.append(expression.toString()).append(", ");
        }

        if (expressions.size() > 0) {
            ret.delete(ret.length() - 2, ret.length());
        }

        return ret.toString();
    }

    @Override
    public void extractAllValues(Expression exp, List<Value> ret) {
        if (exp instanceof Value) {
            ret.add((Value) exp);
        } else {
            Expression[] values = exp.getSubExpressions();

            for (Expression value : values)
                if (!(value instanceof Value))
                    extractAllValues(value, ret);
                else
                    ret.add((Value) value);
        }

    }
}
