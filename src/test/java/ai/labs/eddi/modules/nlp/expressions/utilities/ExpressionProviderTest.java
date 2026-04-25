package ai.labs.eddi.modules.nlp.expressions.utilities;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.value.Value;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ExpressionProvider Tests")
class ExpressionProviderTest {

    private ExpressionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new ExpressionProvider(new ExpressionFactory());
    }

    @Nested
    @DisplayName("createExpression")
    class CreateExpressionTests {

        @Test
        @DisplayName("simple predicate without values")
        void simplePredicate() {
            Expression exp = provider.createExpression("greeting");
            assertNotNull(exp);
            assertEquals("greeting", exp.getExpressionName());
        }

        @Test
        @DisplayName("predicate with single value")
        void predicateWithValue() {
            Expression exp = provider.createExpression("intent", "hello");
            assertNotNull(exp);
            assertEquals("intent", exp.getExpressionName());
            assertTrue(exp.getSubExpressions().length > 0);
        }

        @Test
        @DisplayName("predicate with multiple values")
        void predicateWithMultipleValues() {
            Expression exp = provider.createExpression("relation", "a", "b");
            assertNotNull(exp);
            assertEquals("relation", exp.getExpressionName());
        }
    }

    @Nested
    @DisplayName("parseExpressions")
    class ParseExpressionsTests {

        @Test
        @DisplayName("null input — returns empty Expressions")
        void nullInput() {
            Expressions result = provider.parseExpressions(null);
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("empty string — returns empty Expressions")
        void emptyString() {
            Expressions result = provider.parseExpressions("");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("single expression without parens")
        void singleSimple() {
            Expressions result = provider.parseExpressions("greeting");
            assertEquals(1, result.size());
            assertEquals("greeting", result.get(0).getExpressionName());
        }

        @Test
        @DisplayName("single expression with parens")
        void singleWithParens() {
            Expressions result = provider.parseExpressions("intent(hello)");
            assertEquals(1, result.size());
            assertEquals("intent", result.get(0).getExpressionName());
        }

        @Test
        @DisplayName("multiple comma-separated expressions")
        void multipleExpressions() {
            Expressions result = provider.parseExpressions("greeting,farewell");
            assertEquals(2, result.size());
        }

        @Test
        @DisplayName("nested parentheses parsed correctly")
        void nestedParens() {
            Expressions result = provider.parseExpressions("intent(greeting(hello))");
            assertEquals(1, result.size());
            assertEquals("intent", result.get(0).getExpressionName());
            assertTrue(result.get(0).getSubExpressions().length > 0);
        }

        @Test
        @DisplayName("mixed expressions with and without parens")
        void mixedExpressions() {
            Expressions result = provider.parseExpressions("greeting,intent(hello),farewell");
            assertEquals(3, result.size());
        }

        @Test
        @DisplayName("whitespace is trimmed")
        void whitespace() {
            Expressions result = provider.parseExpressions("  greeting  ");
            assertEquals(1, result.size());
            assertEquals("greeting", result.get(0).getExpressionName());
        }
    }

    @Nested
    @DisplayName("parseExpression")
    class ParseExpressionTests {

        @Test
        @DisplayName("simple word becomes Expression")
        void simpleWord() {
            Expression exp = provider.parseExpression("hello");
            assertNotNull(exp);
            assertEquals("hello", exp.getExpressionName());
        }

        @Test
        @DisplayName("expression with value in parens")
        void withValue() {
            Expression exp = provider.parseExpression("intent(greeting)");
            assertEquals("intent", exp.getExpressionName());
            assertEquals(1, exp.getSubExpressions().length);
        }

        @Test
        @DisplayName("numeric value becomes Value instance")
        void numericValue() {
            Expression exp = provider.parseExpression("42");
            assertInstanceOf(Value.class, exp);
        }

        @Test
        @DisplayName("special expressions — negation, and, or")
        void specialExpressions() {
            assertNotNull(provider.parseExpression("negation"));
            assertNotNull(provider.parseExpression("and"));
            assertNotNull(provider.parseExpression("or"));
        }
    }

    @Nested
    @DisplayName("extractAllValues")
    class ExtractAllValuesTests {

        @Test
        @DisplayName("extract Value from simple expression")
        void simpleValue() {
            Expression exp = provider.parseExpression("42");
            List<Value> values = new ArrayList<>();
            provider.extractAllValues(exp, values);
            assertEquals(1, values.size());
        }

        @Test
        @DisplayName("extract nested values")
        void nestedValues() {
            Expression exp = provider.parseExpression("intent(42)");
            List<Value> values = new ArrayList<>();
            provider.extractAllValues(exp, values);
            assertEquals(1, values.size());
        }

        @Test
        @DisplayName("no values in plain expression")
        void noValues() {
            Expression exp = provider.parseExpression("greeting");
            List<Value> values = new ArrayList<>();
            provider.extractAllValues(exp, values);
            assertTrue(values.isEmpty());
        }
    }
}
