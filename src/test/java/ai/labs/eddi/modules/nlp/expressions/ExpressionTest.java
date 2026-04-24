/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link Expression}. Covers domain parsing, sub-expression
 * manipulation, wildcard equality, cloning, toString formatting, and recursive
 * name search.
 */
class ExpressionTest {

    @Nested
    @DisplayName("construction and naming")
    class Naming {

        @Test
        @DisplayName("simple name without domain")
        void simpleName() {
            var expr = new Expression("greeting");
            assertEquals("greeting", expr.getExpressionName());
            assertNull(expr.getDomain());
        }

        @Test
        @DisplayName("setExpressionName with domain prefix")
        void nameWithDomain() {
            var expr = new Expression("temp");
            expr.setExpressionName("nlp.intent");
            assertEquals("nlp", expr.getDomain());
            assertEquals("intent", expr.getExpressionName());
        }

        @Test
        @DisplayName("setExpressionName starting with dot strips prefix")
        void nameStartingWithDot() {
            var expr = new Expression("temp");
            expr.setExpressionName(".localName");
            assertEquals("localName", expr.getExpressionName());
            assertNull(expr.getDomain());
        }

        @Test
        @DisplayName("numeric value should not be split on dot")
        void numericValue() {
            var expr = new Expression("temp");
            expr.setExpressionName("3.14");
            assertEquals("3.14", expr.getExpressionName());
        }
    }

    @Nested
    @DisplayName("sub-expressions")
    class SubExpressions {

        @Test
        @DisplayName("should add and retrieve sub-expressions")
        void addAndGet() {
            var parent = new Expression("intent");
            var child1 = new Expression("greeting");
            var child2 = new Expression("name");
            parent.addSubExpressions(child1, child2);

            assertEquals(2, parent.getSubExpressions().length);
        }

        @Test
        @DisplayName("should clear sub-expressions")
        void clear() {
            var parent = new Expression("intent", new Expression("child"));
            parent.clearSubExpressions();
            assertEquals(0, parent.getSubExpressions().length);
        }

        @Test
        @DisplayName("should remove specific sub-expression")
        void remove() {
            var child = new Expression("removable");
            var parent = new Expression("intent", child);
            parent.removeSubExpressions(child);
            assertEquals(0, parent.getSubExpressions().length);
        }

        @Test
        @DisplayName("removeSubExpression by index")
        void removeByIndex() {
            var child1 = new Expression("keep");
            var child2 = new Expression("remove");
            var parent = new Expression("p", child1, child2);
            parent.removeSubExpression(1);
            assertEquals(1, parent.getSubExpressions().length);
            assertEquals("keep", parent.getSubExpressions()[0].getExpressionName());
        }

        @Test
        @DisplayName("removeSubExpression with out-of-bounds index should not crash")
        void removeOutOfBounds() {
            var parent = new Expression("p", new Expression("child"));
            parent.removeSubExpression(99); // should not throw
            assertEquals(1, parent.getSubExpressions().length);
        }

        @Test
        @DisplayName("setSubExpression by index")
        void setByIndex() {
            var child = new Expression("old");
            var parent = new Expression("p", child);
            parent.setSubExpression(0, new Expression("new"));
            assertEquals("new", parent.getSubExpressions()[0].getExpressionName());
        }

        @Test
        @DisplayName("setSubExpression with invalid index should not crash")
        void setInvalidIndex() {
            var parent = new Expression("p");
            parent.setSubExpression(-1, new Expression("x")); // should not throw
            parent.setSubExpression(5, new Expression("x")); // should not throw
        }

        @Test
        @DisplayName("addSubExpressions at index")
        void addAtIndex() {
            var parent = new Expression("p", new Expression("a"), new Expression("c"));
            parent.addSubExpressions(1, new Expression("b"));
            assertEquals("b", parent.getSubExpressions()[1].getExpressionName());
        }
    }

    @Nested
    @DisplayName("equality")
    class Equality {

        @Test
        @DisplayName("same name and subs should be equal")
        void equal() {
            var e1 = new Expression("intent", new Expression("greeting"));
            var e2 = new Expression("intent", new Expression("greeting"));
            assertEquals(e1, e2);
        }

        @Test
        @DisplayName("different name should not be equal")
        void differentName() {
            var e1 = new Expression("intent");
            var e2 = new Expression("action");
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("wildcard * should match any expression")
        void wildcardMatches() {
            var wildcard = new Expression("*");
            var specific = new Expression("greeting");
            assertEquals(wildcard, specific);
            assertEquals(specific, wildcard);
        }

        @Test
        @DisplayName("different sub-expression count should not match")
        void differentSubCount() {
            var e1 = new Expression("intent", new Expression("a"));
            var e2 = new Expression("intent", new Expression("a"), new Expression("b"));
            assertNotEquals(e1, e2);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("simple expression without subs")
        void simple() {
            assertEquals("greeting", new Expression("greeting").toString());
        }

        @Test
        @DisplayName("expression with domain")
        void withDomain() {
            var expr = new Expression("intent");
            expr.setDomain("nlp");
            assertEquals("nlp.intent", expr.toString());
        }

        @Test
        @DisplayName("expression with sub-expressions")
        void withSubs() {
            var expr = new Expression("intent", new Expression("greeting"));
            assertEquals("intent(greeting)", expr.toString());
        }

        @Test
        @DisplayName("expression with multiple subs")
        void withMultipleSubs() {
            var expr = new Expression("intent", new Expression("a"), new Expression("b"));
            assertEquals("intent(a, b)", expr.toString());
        }
    }

    @Nested
    @DisplayName("clone")
    class CloneTests {

        @Test
        @DisplayName("safeClone should produce independent copy")
        void safeClone() {
            var original = new Expression("intent", new Expression("greeting"));
            var cloned = original.safeClone();

            assertEquals(original, cloned);
            assertNotSame(original, cloned);
            // Modifying clone should not affect original
            cloned.clearSubExpressions();
            assertEquals(1, original.getSubExpressions().length);
        }
    }

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("getAllExpressionsWithNames should find matching names recursively")
        void findByName() {
            var root = new Expression("intent", new Expression("greeting"), new Expression("name"));
            var result = root.getAllExpressionsWithNames("greeting");
            assertEquals(1, result.size());
            assertEquals("greeting", result.get(0).getExpressionName());
        }

        @Test
        @DisplayName("containsExpressionWithName should return true for match")
        void contains() {
            var root = new Expression("intent", new Expression("greeting"));
            assertTrue(root.containsExpressionWithName("greeting"));
            assertFalse(root.containsExpressionWithName("farewell"));
        }

        @Test
        @DisplayName("should find self if name matches")
        void findSelf() {
            var root = new Expression("intent");
            var result = root.getAllExpressionsWithNames("intent");
            assertEquals(1, result.size());
        }
    }

    @Nested
    @DisplayName("domain logic")
    class DomainLogic {

        @Test
        @DisplayName("canBeConnected with null domain should allow any")
        void nullDomain() {
            var e1 = new Expression("a");
            var e2 = new Expression("b");
            e2.setDomain("nlp");
            assertTrue(e1.canBeConnected(e2));
        }

        @Test
        @DisplayName("canBeConnected with same domain should allow")
        void sameDomain() {
            var e1 = new Expression("a");
            e1.setDomain("nlp");
            var e2 = new Expression("b");
            e2.setDomain("nlp");
            assertTrue(e1.canBeConnected(e2));
        }

        @Test
        @DisplayName("canBeConnected with different domain should deny")
        void differentDomain() {
            var e1 = new Expression("a");
            e1.setDomain("nlp");
            var e2 = new Expression("b");
            e2.setDomain("output");
            assertFalse(e1.canBeConnected(e2));
        }

        @Test
        @DisplayName("getFullExpression returns domain.name")
        void fullExpression() {
            var expr = new Expression("intent");
            expr.setDomain("nlp");
            assertEquals("nlp.intent", expr.getFullExpression());
        }
    }
}
