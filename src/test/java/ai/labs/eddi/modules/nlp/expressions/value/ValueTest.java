/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.expressions.value;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Value} — covers numeric/boolean detection, type conversion,
 * equals/hashCode, and sub-expression rejection.
 */
@DisplayName("Value Expression Tests")
class ValueTest {

    @Nested
    @DisplayName("type detection")
    class TypeDetection {

        @Test
        @DisplayName("should detect integer as numeric")
        void detectsInteger() {
            Value v = new Value("42");
            assertTrue(v.isNumeric());
        }

        @Test
        @DisplayName("should detect double as numeric")
        void detectsDouble() {
            Value v = new Value("3.14");
            assertTrue(v.isDouble());
        }

        @Test
        @DisplayName("should detect non-numeric string")
        void detectsNonNumeric() {
            Value v = new Value("hello");
            assertFalse(v.isNumeric());
        }

        @Test
        @DisplayName("should detect boolean 'true'")
        void detectsBoolTrue() {
            Value v = new Value("true");
            assertTrue(v.isBoolean());
        }

        @Test
        @DisplayName("should detect boolean 'FALSE' (case-insensitive)")
        void detectsBoolFalseCaseInsensitive() {
            Value v = new Value("FALSE");
            assertTrue(v.isBoolean());
        }

        @Test
        @DisplayName("should not detect non-boolean as boolean")
        void notBoolean() {
            Value v = new Value("maybe");
            assertFalse(v.isBoolean());
        }
    }

    @Nested
    @DisplayName("type conversion")
    class TypeConversion {

        @Test
        @DisplayName("should convert to integer")
        void convertsToInteger() {
            Value v = new Value("123");
            assertEquals(123, v.toInteger());
        }

        @Test
        @DisplayName("should convert to float")
        void convertsToFloat() {
            Value v = new Value("2.5");
            assertEquals(2.5f, v.toFloat(), 0.01f);
        }

        @Test
        @DisplayName("should convert to boolean")
        void convertsToBoolean() {
            assertTrue(new Value("true").toBoolean());
            assertFalse(new Value("false").toBoolean());
        }
    }

    @Nested
    @DisplayName("equals and hashCode")
    class EqualsHashCode {

        @Test
        @DisplayName("numeric values should be equal by float comparison")
        void numericEquals() {
            Value v1 = new Value("42");
            Value v2 = new Value("42");
            assertEquals(v1, v2);
        }

        @Test
        @DisplayName("numeric values with different representations should compare as floats")
        void numericEqualsFloat() {
            Value v1 = new Value("42");
            Value v2 = new Value("42.0");
            assertEquals(v1, v2);
        }

        @Test
        @DisplayName("different numeric values should not be equal")
        void numericNotEquals() {
            Value v1 = new Value("42");
            Value v2 = new Value("43");
            assertNotEquals(v1, v2);
        }

        @Test
        @DisplayName("same reference should be equal")
        void sameReference() {
            Value v = new Value("test");
            assertEquals(v, v);
        }

        @Test
        @DisplayName("non-numeric values should use string comparison")
        void nonNumericEquals() {
            Value v1 = new Value("hello");
            Value v2 = new Value("hello");
            assertEquals(v1, v2);
        }

        @Test
        @DisplayName("numeric value hashCode should use float")
        void numericHashCode() {
            Value v1 = new Value("42");
            Value v2 = new Value("42.0");
            assertEquals(v1.hashCode(), v2.hashCode());
        }

        @Test
        @DisplayName("non-numeric value hashCode should use super")
        void nonNumericHashCode() {
            Value v = new Value("hello");
            // Should not throw
            assertDoesNotThrow(v::hashCode);
        }
    }

    @Nested
    @DisplayName("sub-expression rejection")
    class SubExpressions {

        @Test
        @DisplayName("setSubExpressions should be no-op (logged warning)")
        void setSubExpressionsNoOp() {
            Value v = new Value("test");
            assertDoesNotThrow(() -> v.setSubExpressions());
        }

        @Test
        @DisplayName("addSubExpressions should be no-op (logged warning)")
        void addSubExpressionsNoOp() {
            Value v = new Value("test");
            assertDoesNotThrow(() -> v.addSubExpressions());
        }
    }

    @Nested
    @DisplayName("exact numeric comparison and robustness")
    class ExactComparison {

        @Test
        @DisplayName("numbers above 2^24 do not collide")
        void bigNumbersDoNotCollide() {
            assertNotEquals(new Value("123456789"), new Value("123456790"));
        }

        @Test
        @DisplayName("1, 1.0 and 1,0 are equal and hash alike")
        void sameNumberDifferentSpelling() {
            assertEquals(new Value("1"), new Value("1.0"));
            assertEquals(new Value("1"), new Value("1,0"));
            assertEquals(new Value("1").hashCode(), new Value("1,0").hashCode());
        }

        @Test
        @DisplayName("unicode digits are read as numbers, compare exactly and never throw")
        void unicodeDigitsAreNumbersAndCompareExactly() {
            for (String digits : new String[]{"\uFF11\uFF12", "\u0661\u0662", "\u0967\u0968", "\u0E51\u0E52"}) {
                Value value = new Value(digits);
                assertTrue(value.isNumeric(), digits);
                assertEquals(12, value.toInteger(), digits);
                assertEquals(new Value("12"), value, digits);
                assertEquals(new Value("12").hashCode(), value.hashCode(), digits);
                assertNotEquals(new Value("13"), value, digits);
                assertDoesNotThrow(() -> value.equals(new Value("1")));
            }
        }

        @Test
        @DisplayName("a numeric value compared with a non-numeric one does not throw")
        void numericVersusText() {
            assertDoesNotThrow(() -> new Value("1").equals(new Value("abc")));
            assertNotEquals(new Value("1"), new Value("abc"));
        }

        @Test
        @DisplayName("an over-long number is compared as text and does not throw")
        void overLongNumber() {
            String digits = "9".repeat(5000);
            assertEquals(new Value(digits), new Value(digits));
            assertEquals(new Value(digits).hashCode(), new Value(digits).hashCode());
            assertNotEquals(new Value(digits), new Value(digits + "8"));
        }
    }
}
