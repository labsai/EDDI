package ai.labs.property.impl;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.value.Value;
import ai.labs.models.Property;
import ai.labs.property.IPropertySetter;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.models.Property.Scope.conversation;

/**
 * @author ginccc
 */
public class PropertySetter implements IPropertySetter {
    private static final String PROPERTY_EXPRESSION = "property";

    @Override
    public List<Property> extractProperties(Expressions expressions) {
        return expressions.stream().
                filter(expression ->
                        PROPERTY_EXPRESSION.equals(expression.getExpressionName()) &&
                                expression.getSubExpressions().length > 0).
                map(expression -> {
                    List<String> meanings = new LinkedList<>();
                    Value value = new Value();
                    extractMeanings(meanings, value, expression.getSubExpressions()[0]);
                    return new Property(String.join(".", meanings), value.getExpressionName(), conversation);
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
