/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.properties.IPropertySetter;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static ai.labs.eddi.configs.properties.model.Property.Scope.*;

/**
 * @author ginccc
 */

public class PropertySetter implements IPropertySetter {
    private static final String PROPERTY_EXPRESSION = "property";
    /**
     * At most 19 ASCII digits and no leading zero; whether it fits a long is
     * decided by parsing it.
     */
    private static final Pattern PLAIN_INTEGER = Pattern.compile("0|[1-9][0-9]{0,18}");

    private final List<SetOnActions> setOnActionsList;

    private final boolean acceptClientContextProperties;

    public PropertySetter(List<SetOnActions> setOnActionsList) {
        this(setOnActionsList, false);
    }

    public PropertySetter(List<SetOnActions> setOnActionsList, boolean acceptClientContextProperties) {
        this.setOnActionsList = setOnActionsList;
        this.acceptClientContextProperties = acceptClientContextProperties;
    }

    @Override
    public boolean acceptsClientContextProperties() {
        return acceptClientContextProperties;
    }

    @Override
    public List<Property> extractProperties(Expressions expressions) {
        return expressions.stream()
                .filter(expression -> PROPERTY_EXPRESSION.equals(expression.getExpressionName()) && expression.getSubExpressions().length > 0)
                .map(expression -> {
                    var meanings = new LinkedList<String>();
                    String leaf = extractMeanings(meanings, expression.getSubExpressions()[0]);

                    var propertyScope = extractScopeOrDefault(expression.getSubExpressions());
                    var propertyName = String.join(".", meanings);

                    return toProperty(propertyName, leaf, propertyScope);
                }).collect(Collectors.toCollection(LinkedList::new));
    }

    /**
     * The typed property for a leaf value. The text is kept verbatim — host names,
     * versions ({@code 1.2.3}), codes with a leading zero ({@code 01234}), decimals
     * and signed numbers stay strings — except a plain integer (ASCII digits, no
     * leading zero), which becomes an {@code Integer}, or a {@code Long} beyond the
     * int range, and {@code true}/{@code false}. Nothing here can throw.
     */
    static Property toProperty(String name, String leaf, Property.Scope scope) {
        if (leaf == null) {
            return new Property(name, (String) null, scope);
        }
        if (PLAIN_INTEGER.matcher(leaf).matches()) {
            try {
                long number = Long.parseLong(leaf);
                if (number <= Integer.MAX_VALUE) {
                    return new Property(name, (int) number, scope);
                }
                return new Property(name, number, scope);
            } catch (NumberFormatException e) {
                // beyond the long range (19 digits above 9223372036854775807): keep the text
                return new Property(name, leaf, scope);
            }
        }
        if (leaf.equalsIgnoreCase("true") || leaf.equalsIgnoreCase("false")) {
            return new Property(name, Boolean.parseBoolean(leaf), scope);
        }
        return new Property(name, leaf, scope);
    }

    private static String extractMeanings(List<String> meanings, Expression expression) {
        if (expression.getSubExpressions().length > 0) {
            meanings.add(expression.getExpressionName());
            return extractMeanings(meanings, expression.getSubExpressions()[0]);
        }
        return expression.getExpressionName();
    }

    private static Property.Scope extractScopeOrDefault(Expression[] subExpressions) {
        Property.Scope propertyScope = conversation;
        if (subExpressions.length > 1 && subExpressions[1] != null) {
            switch (subExpressions[1].getExpressionName()) {
                case "step" -> propertyScope = step;
                // case "conversation" -> propertyScope = conversation;
                case "longTerm" -> propertyScope = longTerm;
                default -> {
                }
            }
        }
        return propertyScope;
    }

    public List<SetOnActions> getSetOnActionsList() {
        return setOnActionsList;
    }
}
