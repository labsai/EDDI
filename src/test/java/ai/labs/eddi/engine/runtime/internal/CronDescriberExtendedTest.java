package ai.labs.eddi.engine.runtime.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for {@link CronDescriber} — covers weekends, specific months,
 * ordinal edge cases (11th-13th), formatTime fallback, and mixed expressions.
 */
@DisplayName("CronDescriber Extended Tests")
class CronDescriberExtendedTest {

    @Nested
    @DisplayName("weekends and weekdays")
    class DowPatterns {

        @Test
        @DisplayName("should describe weekends (0,6)")
        void weekends() {
            String result = CronDescriber.describe("0 9 * * 0,6");
            assertTrue(result.contains("weekend"));
        }

        @Test
        @DisplayName("should describe weekdays (MON-FRI)")
        void weekdays() {
            String result = CronDescriber.describe("0 9 * * 1-5");
            assertTrue(result.contains("weekday"));
        }

        @Test
        @DisplayName("should describe specific days (MON,WED,FRI)")
        void specificDays() {
            String result = CronDescriber.describe("0 9 * * 1,3,5");
            assertTrue(result.contains("Monday") || result.contains("Wednesday") || result.contains("Friday"));
        }
    }

    @Nested
    @DisplayName("months")
    class MonthPatterns {

        @Test
        @DisplayName("should describe specific month")
        void specificMonth() {
            String result = CronDescriber.describe("0 0 1 6 *");
            assertTrue(result.contains("June"));
        }

        @Test
        @DisplayName("should describe month range")
        void monthRange() {
            String result = CronDescriber.describe("0 9 * 1-3 *");
            assertTrue(result.contains("January") || result.contains("February") || result.contains("March"));
        }

        @Test
        @DisplayName("should describe dom with every month")
        void domEveryMonth() {
            String result = CronDescriber.describe("0 0 15 * *");
            assertTrue(result.contains("every month"));
        }
    }

    @Nested
    @DisplayName("ordinals")
    class OrdinalPatterns {

        @Test
        @DisplayName("should format 1st correctly")
        void first() {
            String result = CronDescriber.describe("0 0 1 * *");
            assertTrue(result.contains("1st"));
        }

        @Test
        @DisplayName("should format 2nd correctly")
        void second() {
            String result = CronDescriber.describe("0 0 2 * *");
            assertTrue(result.contains("2nd"));
        }

        @Test
        @DisplayName("should format 3rd correctly")
        void third() {
            String result = CronDescriber.describe("0 0 3 * *");
            assertTrue(result.contains("3rd"));
        }

        @Test
        @DisplayName("should format 11th correctly (not 11st)")
        void eleventh() {
            String result = CronDescriber.describe("0 0 11 * *");
            assertTrue(result.contains("11th"));
        }

        @Test
        @DisplayName("should format 12th correctly (not 12nd)")
        void twelfth() {
            String result = CronDescriber.describe("0 0 12 * *");
            assertTrue(result.contains("12th"));
        }

        @Test
        @DisplayName("should format 13th correctly (not 13rd)")
        void thirteenth() {
            String result = CronDescriber.describe("0 0 13 * *");
            assertTrue(result.contains("13th"));
        }

        @Test
        @DisplayName("should format 21st correctly")
        void twentyFirst() {
            String result = CronDescriber.describe("0 0 21 * *");
            assertTrue(result.contains("21st"));
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("should handle null expression")
        void nullExpression() {
            assertEquals("Invalid cron expression", CronDescriber.describe(null));
        }

        @Test
        @DisplayName("should handle blank expression")
        void blankExpression() {
            assertEquals("Invalid cron expression", CronDescriber.describe("   "));
        }

        @Test
        @DisplayName("should handle wrong number of fields")
        void wrongFields() {
            assertTrue(CronDescriber.describe("* * *").contains("expected 5 fields"));
        }

        @Test
        @DisplayName("should describe step hours")
        void stepHours() {
            String result = CronDescriber.describe("0 */4 * * *");
            assertTrue(result.contains("4 hours"));
        }

        @Test
        @DisplayName("should describe minute-only pattern")
        void minuteOnly() {
            String result = CronDescriber.describe("30 * * * *");
            assertTrue(result.contains("minute 30"));
        }

        @Test
        @DisplayName("should describe month with dom together")
        void monthWithDom() {
            // "At midnight on the 1st of January"
            String result = CronDescriber.describe("0 0 1 1 *");
            assertTrue(result.contains("1st") && result.contains("January"));
        }
    }

    @Nested
    @DisplayName("formatSet bounds check (CodeQL fix)")
    class FormatSetBoundsCheck {

        @Test
        @DisplayName("should fall back to String.valueOf for negative value in single-element set")
        void negativeValueSingleElement() throws Exception {
            java.lang.reflect.Method formatSet = CronDescriber.class.getDeclaredMethod("formatSet", java.util.Set.class, String[].class);
            formatSet.setAccessible(true);

            String[] labels = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
            java.util.Set<Integer> negativeSet = java.util.Set.of(-1);

            // Before fix: ArrayIndexOutOfBoundsException
            // After fix: returns "-1" (String.valueOf)
            String result = (String) formatSet.invoke(null, negativeSet, labels);
            assertEquals("-1", result);
        }

        @Test
        @DisplayName("should fall back to String.valueOf for negative values in multi-element set")
        void negativeValueMultiElement() throws Exception {
            java.lang.reflect.Method formatSet = CronDescriber.class.getDeclaredMethod("formatSet", java.util.Set.class, String[].class);
            formatSet.setAccessible(true);

            String[] labels = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
            java.util.Set<Integer> mixedSet = new java.util.TreeSet<>(java.util.List.of(-1, 0, 99));

            String result = (String) formatSet.invoke(null, mixedSet, labels);
            assertTrue(result.contains("-1"), "negative value should be rendered as string");
            assertTrue(result.contains("Sun"), "valid index should use label");
            assertTrue(result.contains("99"), "out-of-bounds positive should use string");
        }

        @Test
        @DisplayName("should handle value equal to labels.length (out of bounds)")
        void valueAtBoundary() throws Exception {
            java.lang.reflect.Method formatSet = CronDescriber.class.getDeclaredMethod("formatSet", java.util.Set.class, String[].class);
            formatSet.setAccessible(true);

            String[] labels = {"A", "B", "C"};
            // v=3 is exactly labels.length, should fall back to String.valueOf
            String result = (String) formatSet.invoke(null, java.util.Set.of(3), labels);
            assertEquals("3", result);
        }

        @Test
        @DisplayName("should handle valid value at max index")
        void validMaxIndex() throws Exception {
            java.lang.reflect.Method formatSet = CronDescriber.class.getDeclaredMethod("formatSet", java.util.Set.class, String[].class);
            formatSet.setAccessible(true);

            String[] labels = {"A", "B", "C"};
            String result = (String) formatSet.invoke(null, java.util.Set.of(2), labels);
            assertEquals("C", result);
        }
    }
}
