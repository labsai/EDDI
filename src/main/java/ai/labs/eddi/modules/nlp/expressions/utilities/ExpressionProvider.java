package ai.labs.eddi.modules.nlp.expressions.utilities;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.IExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.value.Value;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

@ApplicationScoped
public class ExpressionProvider implements IExpressionProvider {
    private final IExpressionFactory expressionFactory;

    private static final Logger log = Logger.getLogger(ExpressionProvider.class);

    @Inject
    public ExpressionProvider(IExpressionFactory expressionFactory) {
        this.expressionFactory = expressionFactory;
    }

    @Override
    public Expression createExpression(String predicate, Object... values) {
        StringBuilder sb = new StringBuilder(predicate);
        if (values != null && values.length > 0) {
            sb.append("(");

            Arrays.stream(values).forEach(value -> sb.append(value).append(","));

            sb.deleteCharAt(sb.length() - 1);
            sb.append(")");
        }

        return parseExpression(sb.toString());
    }

    @Override
    public Expressions parseExpressions(String expressions) {
        if (isNullOrEmpty(expressions)) {
            return new Expressions();
        }

        List<String> listStringExpressions = new ArrayList<>();

        expressions = expressions.trim();

        int lastPos = 0;
        int parenthesis = -1;
        String expressionPart;
        for (int i = 0; i < expressions.length(); i++) {
            if (i != 0 && parenthesis < 1 && expressions.charAt(i) == ',' && expressions.charAt(i - 1) != ')') {
                expressionPart = expressions.substring(lastPos, i);
                if (!expressionPart.isEmpty()) {
                    listStringExpressions.add(expressionPart);
                }
                lastPos = i + 1;
            }

            if (expressions.charAt(i) == '(') {
                if (parenthesis == -1) {
                    parenthesis = 0;
                }
                parenthesis++;
            }

            if (expressions.charAt(i) == ')') {
                parenthesis--;
            }

            if (parenthesis == 0) {
                expressionPart = expressions.substring(lastPos, i + 1);
                if (!expressionPart.isEmpty()) {
                    listStringExpressions.add(expressionPart);
                }
                parenthesis = -1;
                lastPos = i + 2;
            }
        }

        if (lastPos < expressions.length()) {
            expressionPart = expressions.substring(lastPos);
            if (!expressionPart.isEmpty()) {
                listStringExpressions.add(expressionPart);
            }
        }

        return listStringExpressions.stream().map(this::parseExpression).collect(Collectors.toCollection(Expressions::new));

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
            try {
                String subExpressions = expression.substring(indexOfOpening + 1, indexOfClosing);
                Expressions expressions = parseExpressions(subExpressions);
                exp.setSubExpressions(expressions);
            } catch (Exception e) {
                log.error(format("Error while parsing Expression: %s, indexOfOpening: %s, indexOfClosing: %s, message: %s",
                        expression, indexOfOpening, indexOfClosing, e.getLocalizedMessage()));
            }
        } else {
            exp = new Expression(expression);
        }

        return expressionFactory.getExpression(exp);
    }

    @Override
    public void extractAllValues(Expression exp, List<Value> ret) {
        if (exp instanceof Value) {
            ret.add((Value) exp);
        } else {
            Expression[] values = exp.getSubExpressions();

            Arrays.stream(values).forEach(value -> {
                if (!(value instanceof Value)) {
                    extractAllValues(value, ret);
                } else {
                    ret.add((Value) value);
                }
            });
        }

    }
}
