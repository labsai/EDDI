package ai.labs.property.impl;

import ai.labs.expressions.Expression;
import ai.labs.expressions.value.Value;
import ai.labs.property.IPropertySetter;
import ai.labs.property.model.PropertyEntry;

import javax.enterprise.context.ApplicationScoped;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PropertySetter implements IPropertySetter {
    private static final String PROPERTY_EXPRESSION = "property";

    @Override
    public List<PropertyEntry> extractProperties(List<Expression> expressions) {
        return expressions.stream().
                filter(expression ->
                        PROPERTY_EXPRESSION.equals(expression.getExpressionName()) &&
                                expression.getSubExpressions().length > 0).
                map(expression -> {
                    List<String> meanings = new LinkedList<>();
                    Value value = new Value();
                    extractMeanings(meanings, value, expression.getSubExpressions()[0]);
                    return new PropertyEntry(meanings, value.getExpressionName());
                }).collect(Collectors.toCollection(LinkedList::new));
    }

    private void extractMeanings(List<String> meanings, Value value, Expression expression) {
        if (expression.getSubExpressions().length > 0) {
            meanings.add(expression.getExpressionName());
            extractMeanings(meanings, value, expression.getSubExpressions()[0]);
        } else {
            value.setExpressionName(expression.getExpressionName());
        }
    }
}
