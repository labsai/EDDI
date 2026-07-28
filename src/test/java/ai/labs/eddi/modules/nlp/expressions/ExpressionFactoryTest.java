/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions;

import ai.labs.eddi.modules.nlp.expressions.value.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("ExpressionFactory")
class ExpressionFactoryTest {

    private ExpressionFactory factory;

    @BeforeEach
    void setUp() {
        factory = new ExpressionFactory();
    }

    @Test
    @DisplayName("keeps the domain of nested expressions when building a connector")
    void connectorKeepsSubExpressionDomain() {
        var subExpression = new Expression("category");
        subExpression.setDomain("food");

        Expression result = factory.getExpression(new Expression("and", subExpression));

        assertInstanceOf(Connector.class, result);
        assertEquals(1, result.getSubExpressions().length);
        assertEquals("food", result.getSubExpressions()[0].getDomain(),
                "the factory used to self-assign the connector's own (null) domain, which wiped the domain of every child");
    }

    @Test
    @DisplayName("does not invent a domain for expressions that never had one")
    void unknownExpressionHasNoDomain() {
        Expression result = factory.getExpression(new Expression("category", new Expression("billing")));

        assertEquals("category", result.getExpressionName());
        assertNull(result.getDomain());
    }

    @Test
    @DisplayName("numeric leaf expressions become Value instances")
    void numericExpressionBecomesValue() {
        Expression result = factory.getExpression(new Expression("42"));

        assertInstanceOf(Value.class, result);
        assertEquals("42", result.getExpressionName());
    }

    @Test
    @DisplayName("registered keywords are resolved to their prototype type")
    void registeredKeywordsResolveToPrototypes() {
        assertInstanceOf(Negation.class, factory.getExpression(new Expression("negation")));
        assertInstanceOf(Ignored.class, factory.getExpression(new Expression("ignored")));
    }

    @Test
    @DisplayName("a null expression is passed through unchanged")
    void nullExpressionIsPassedThrough() {
        assertNull(factory.getExpression((Expression) null));
    }
}
