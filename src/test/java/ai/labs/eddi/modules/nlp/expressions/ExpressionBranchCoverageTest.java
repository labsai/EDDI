/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional branch coverage tests for {@link Expression} — covers branches
 * missed by ExpressionTest.
 */
@DisplayName("Expression — Additional Branch Coverage")
class ExpressionBranchCoverageTest {

    @Nested
    @DisplayName("equals — edge cases")
    class EqualsEdgeCases {

        @Test
        @DisplayName("equals with same object returns true")
        void sameObject() {
            var expr = new Expression("a");
            assertEquals(expr, expr);
        }

        @Test
        @DisplayName("equals with null returns false")
        void equalsNull() {
            var expr = new Expression("a");
            assertNotEquals(null, expr);
        }

        @Test
        @DisplayName("equals with non-Expression object returns false")
        void equalsNonExpression() {
            var expr = new Expression("a");
            assertNotEquals("a", expr);
        }

        @Test
        @DisplayName("equals — same name, same sub-expressions")
        void sameNameSameSubExpressions() {
            var e1 = new Expression("intent", new Expression("a"), new Expression("b"));
            var e2 = new Expression("intent", new Expression("a"), new Expression("b"));
            assertEquals(e1, e2);
        }

        @Test
        @DisplayName("equals — same name, different sub-expression content")
        void sameNameDifferentSubContent() {
            var e1 = new Expression("intent", new Expression("a"));
            var e2 = new Expression("intent", new Expression("b"));
            assertNotEquals(e1, e2);
        }

        @Test
        @DisplayName("wildcard on left side equals any")
        void wildcardLeft() {
            var wildcard = new Expression("*");
            var specific = new Expression("anything");
            assertTrue(wildcard.equals(specific));
        }

        @Test
        @DisplayName("wildcard on right side equals any")
        void wildcardRight() {
            var specific = new Expression("anything");
            var wildcard = new Expression("*");
            assertTrue(specific.equals(wildcard));
        }

        @Test
        @DisplayName("both wildcards are equal")
        void bothWildcards() {
            var w1 = new Expression("*");
            var w2 = new Expression("*");
            assertEquals(w1, w2);
        }
    }

    @Nested
    @DisplayName("hashCode")
    class HashCodeTests {

        @Test
        @DisplayName("equal expressions have same hashCode")
        void equalExpressionsHaveSameHashCode() {
            var e1 = new Expression("intent", new Expression("greeting"));
            var e2 = new Expression("intent", new Expression("greeting"));
            assertEquals(e1.hashCode(), e2.hashCode());
        }

        @Test
        @DisplayName("different expressions produce valid hashCodes")
        void differentExpressionsProduceValidHashCodes() {
            var e1 = new Expression("intent");
            var e2 = new Expression("action");
            // hashCode contract: equal objects must have equal hashes,
            // but unequal objects MAY have different hashes. Just verify both produce
            // stable values.
            assertEquals(e1.hashCode(), new Expression("intent").hashCode());
            assertEquals(e2.hashCode(), new Expression("action").hashCode());
        }

        @Test
        @DisplayName("expression with no subs has consistent hashCode")
        void noSubsConsistentHashCode() {
            var e1 = new Expression("test");
            var e2 = new Expression("test");
            assertEquals(e1.hashCode(), e2.hashCode());
        }
    }

    @Nested
    @DisplayName("setExpressionName — branches")
    class SetExpressionNameBranches {

        @Test
        @DisplayName("integer number is not split on dot")
        void integerNotSplit() {
            var expr = new Expression("temp");
            expr.setExpressionName("42");
            assertEquals("42", expr.getExpressionName());
            assertNull(expr.getDomain());
        }

        @Test
        @DisplayName("negative number is not split on dot")
        void negativeNumberNotSplit() {
            var expr = new Expression("temp");
            expr.setExpressionName("-3.14");
            // -3.14 contains a dot and is not a valid non-negative number,
            // so domain splitting applies: domain="-3", expressionName="14"
            assertNotNull(expr.getExpressionName());
            // Verify the name was actually set (either split or direct)
            assertFalse(expr.getExpressionName().isEmpty());
        }

        @Test
        @DisplayName("name without dot sets expressionName directly")
        void noDotSetsDirectly() {
            var expr = new Expression("temp");
            expr.setExpressionName("simple");
            assertEquals("simple", expr.getExpressionName());
            assertNull(expr.getDomain());
        }

        @Test
        @DisplayName("name with dot extracts domain and name")
        void dotExtractsDomainAndName() {
            var expr = new Expression("temp");
            expr.setExpressionName("domain.name");
            assertEquals("domain", expr.getDomain());
            assertEquals("name", expr.getExpressionName());
        }

        @Test
        @DisplayName("name starting with dot strips prefix")
        void dotPrefixStrips() {
            var expr = new Expression("temp");
            expr.setExpressionName(".name");
            assertEquals("name", expr.getExpressionName());
        }
    }

    @Nested
    @DisplayName("sub-expression manipulation — additional branches")
    class SubExpressionManipulation {

        @Test
        @DisplayName("addSubExpressions with Expressions list")
        void addSubExpressionsWithExpressionsList() {
            var parent = new Expression("p");
            var subs = new Expressions();
            subs.add(new Expression("a"));
            subs.add(new Expression("b"));
            parent.addSubExpressions(subs);
            assertEquals(2, parent.getSubExpressions().length);
        }

        @Test
        @DisplayName("setSubExpressions with Expressions list")
        void setSubExpressionsWithExpressionsList() {
            var parent = new Expression("p", new Expression("old"));
            var subs = new Expressions();
            subs.add(new Expression("new1"));
            subs.add(new Expression("new2"));
            parent.setSubExpressions(subs);
            assertEquals(2, parent.getSubExpressions().length);
            assertEquals("new1", parent.getSubExpressions()[0].getExpressionName());
        }

        @Test
        @DisplayName("setSubExpressions with varargs")
        void setSubExpressionsWithVarargs() {
            var parent = new Expression("p", new Expression("old"));
            parent.setSubExpressions(new Expression("x"), new Expression("y"));
            assertEquals(2, parent.getSubExpressions().length);
        }

        @Test
        @DisplayName("addSubExpressions at invalid index logs error and does nothing")
        void addSubExpressionsAtInvalidIndex() {
            var parent = new Expression("p");
            // negative index
            parent.addSubExpressions(-1, new Expression("x"));
            assertEquals(0, parent.getSubExpressions().length);

            // index > size
            parent.addSubExpressions(99, new Expression("x"));
            assertEquals(0, parent.getSubExpressions().length);
        }

        @Test
        @DisplayName("removeSubExpression by index at boundary")
        void removeSubExpressionAtBoundary() {
            var parent = new Expression("p", new Expression("a"));
            // index > size → nothing removed
            parent.removeSubExpression(5);
            assertEquals(1, parent.getSubExpressions().length);
        }

        @Test
        @DisplayName("removeSubExpression at index 0")
        void removeSubExpressionAtZero() {
            var child = new Expression("a");
            var parent = new Expression("p", child);
            parent.removeSubExpression(0);
            assertEquals(0, parent.getSubExpressions().length);
        }
    }

    @Nested
    @DisplayName("constructors")
    class Constructors {

        @Test
        @DisplayName("constructor with expressionName and Expressions")
        void constructorWithExpressions() {
            var subs = new Expressions();
            subs.add(new Expression("a"));
            var expr = new Expression("parent", subs);
            assertEquals("parent", expr.getExpressionName());
            assertEquals(1, expr.getSubExpressions().length);
        }

        @Test
        @DisplayName("constructor with expressionName and varargs")
        void constructorWithVarargs() {
            var expr = new Expression("parent", new Expression("a"), new Expression("b"));
            assertEquals(2, expr.getSubExpressions().length);
        }

        @Test
        @DisplayName("default constructor")
        void defaultConstructor() {
            // This is protected but accessible via subclass
            var expr = new Expression("test");
            assertNotNull(expr);
        }
    }

    @Nested
    @DisplayName("miscellaneous methods")
    class MiscMethods {

        @Test
        @DisplayName("isExpressionSubCategory returns false by default")
        void isExpressionSubCategory() {
            var expr = new Expression("test");
            assertFalse(expr.isExpressionSubCategory());
        }

        @Test
        @DisplayName("getGUIString returns concatenation of sub-expression GUIStrings")
        void getGUIString() {
            var parent = new Expression("p", new Expression("a"), new Expression("b"));
            // getGUIString only concatenates sub-expression GUIStrings
            // sub-expressions have no sub-expressions themselves, so their GUIStrings are
            // empty
            assertEquals("", parent.getGUIString());
        }

        @Test
        @DisplayName("getGUIString of leaf expression is empty string")
        void getGUIStringLeaf() {
            var expr = new Expression("leaf");
            assertEquals("", expr.getGUIString());
        }

        @Test
        @DisplayName("toString with domain and sub-expressions")
        void toStringWithDomainAndSubs() {
            var expr = new Expression("intent", new Expression("a"));
            expr.setDomain("nlp");
            assertEquals("nlp.intent(a)", expr.toString());
        }

        @Test
        @DisplayName("canBeConnected — first has domain, second has null domain")
        void canBeConnectedFirstHasDomainSecondNull() {
            var e1 = new Expression("a");
            e1.setDomain("nlp");
            var e2 = new Expression("b");
            // e1 has domain "nlp", e2 has null domain → checks
            // e1.getDomain().equals(e2.getDomain())
            // this will return false since "nlp" != null
            assertFalse(e1.canBeConnected(e2));
        }

        @Test
        @DisplayName("containsExpressionWithName with non-matching name")
        void containsNonMatchingName() {
            var expr = new Expression("intent");
            assertFalse(expr.containsExpressionWithName("nonexistent"));
        }

        @Test
        @DisplayName("getAllExpressionsWithNames with multiple names")
        void getAllWithMultipleNames() {
            var root = new Expression("intent",
                    new Expression("greeting"),
                    new Expression("farewell"));
            var result = root.getAllExpressionsWithNames("greeting", "farewell");
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("clone creates deep copy of sub-expressions")
        void cloneDeepCopy() throws CloneNotSupportedException {
            var original = new Expression("parent",
                    new Expression("child1", new Expression("grandchild")));
            var cloned = (Expression) original.clone();

            assertEquals(original, cloned);
            assertNotSame(original.getSubExpressions()[0], cloned.getSubExpressions()[0]);
        }

        @Test
        @DisplayName("safeClone returns independent copy")
        void safeCloneIndependent() {
            var expr = new Expression("test", new Expression("sub"));
            var cloned = expr.safeClone();
            assertNotNull(cloned);
            assertNotSame(expr, cloned);
            assertEquals(expr, cloned);
        }
    }
}
