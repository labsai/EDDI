package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.models.Property;
import ai.labs.eddi.models.SetOnActions;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.value.Value;
import ai.labs.eddi.modules.properties.IPropertySetter;
import lombok.Getter;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import static ai.labs.eddi.models.Property.Scope.*;

/**
 * @author ginccc
 */

public class PropertySetter implements IPropertySetter {
    private static final String PROPERTY_EXPRESSION = "property";

    @Getter
    private final List<SetOnActions> setOnActionsList;

    public PropertySetter(List<SetOnActions> setOnActionsList) {
        this.setOnActionsList = setOnActionsList;
    }

    @Override
    public List<Property> extractProperties(Expressions expressions) {
        return expressions.stream().
                filter(expression ->
                        PROPERTY_EXPRESSION.equals(expression.getExpressionName()) &&
                                expression.getSubExpressions().length > 0).
                map(expression -> {
                    var meanings = new LinkedList<String>();
                    var propertyValue = new Value();
                    extractMeanings(meanings, propertyValue, expression.getSubExpressions()[0]);

                    var propertyScope = extractScopeOrDefault(expression.getSubExpressions());
                    var propertyName = String.join(".", meanings);

                    if (propertyValue.isNumeric()) {
                        if (propertyValue.isDouble()) {
                            return new Property(propertyName, propertyValue.toFloat(), propertyScope);
                        } else {
                            return new Property(propertyName, propertyValue.toInteger(), propertyScope);
                        }
                    } else {
                        return propertyValue.isBoolean() ?
                                new Property(propertyName, propertyValue.toBoolean(), propertyScope) :
                                new Property(propertyName, propertyValue.getExpressionName(), propertyScope);
                    }

                }).collect(Collectors.toCollection(LinkedList::new));
    }

    private static void extractMeanings(List<String> meanings, Value value, Expression expression) {
        if (expression.getSubExpressions().length > 0) {
            meanings.add(expression.getExpressionName());
            extractMeanings(meanings, value, expression.getSubExpressions()[0]);
        } else {
            value.setExpressionName(expression.getExpressionName());
        }
    }

    private static Property.Scope extractScopeOrDefault(Expression[] subExpressions) {
        Property.Scope propertyScope = conversation;
        if (subExpressions.length > 1 && subExpressions[1] != null) {
            switch (subExpressions[1].getExpressionName()) {
                case "step" -> propertyScope = step;
                // case "conversation" -> propertyScope = conversation;
                case "longTerm" -> propertyScope = longTerm;
            }
        }
        return propertyScope;
    }
}
