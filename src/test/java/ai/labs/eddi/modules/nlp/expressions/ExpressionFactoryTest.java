/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions;

import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
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

        Expression result = factory.getExpression(new Expression("and", subExpression, new Expression("size")));

        assertInstanceOf(Connector.class, result);
        assertEquals(2, result.getSubExpressions().length);
        assertEquals("food", result.getSubExpressions()[0].getDomain(),
                "the factory used to self-assign the connector's own (null) domain, and Connector.setDomain propagates it, "
                        + "which wiped the domain of every child");
        assertNull(result.getDomain(), "the connector itself never had a domain");
        assertEquals("and(food.category, size)", result.toString(), "used to render as 'and(category, size)'");
    }

    @Test
    @DisplayName("parsing a domain-qualified expression is unaffected — the parser never splits a domain off the name")
    void parsingDomainQualifiedExpressionIsUnchanged() {
        // Expression(String) assigns the name verbatim (only setExpressionName splits
        // on '.'), so every expression coming out of the parser carries a null domain.
        // That is why dropping the old self-assignment cannot change how stored
        // behaviour rules or dictionary entries render or match.
        var provider = new ExpressionProvider(factory);

        Expression parsed = provider.parseExpression("and(food.category, size)");

        assertInstanceOf(Connector.class, parsed);
        assertEquals("and(food.category, size)", parsed.toString());
        assertEquals("food.category", parsed.getSubExpressions()[0].getExpressionName());
        assertNull(parsed.getSubExpressions()[0].getDomain());
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
