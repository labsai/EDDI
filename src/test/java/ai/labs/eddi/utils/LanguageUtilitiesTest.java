package ai.labs.eddi.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LanguageUtilities} — time expression parsing and ordinal
 * number recognition.
 */
@DisplayName("LanguageUtilities")
class LanguageUtilitiesTest {

    @Nested
    @DisplayName("isTimeExpression")
    class TimeExpressionTests {

        @ParameterizedTest
        @ValueSource(strings = {"12h10", "15h30", "08h00", "23h59"})
        @DisplayName("recognizes 'Xh' format with minutes")
        void hourHMinuteFormat(String input) {
            Date result = LanguageUtilities.isTimeExpression(input);
            assertNotNull(result, "Should recognize " + input + " as time");
        }

        @ParameterizedTest
        @ValueSource(strings = {"15h", "08h", "23h"})
        @DisplayName("recognizes 'Xh' format without minutes")
        void hourHOnlyFormat(String input) {
            Date result = LanguageUtilities.isTimeExpression(input);
            assertNotNull(result, "Should recognize " + input + " as time");
        }

        @ParameterizedTest
        @ValueSource(strings = {"19:50", "00:00", "23:59", "8:30"})
        @DisplayName("recognizes HH:MM format")
        void colonFormat(String input) {
            Date result = LanguageUtilities.isTimeExpression(input);
            assertNotNull(result, "Should recognize " + input + " as time");
        }

        @ParameterizedTest
        @ValueSource(strings = {"13:50:12", "00:00:00", "23:59:59"})
        @DisplayName("recognizes HH:MM:SS format")
        void fullFormat(String input) {
            Date result = LanguageUtilities.isTimeExpression(input);
            assertNotNull(result, "Should recognize " + input + " as time");
        }

        @Test
        @DisplayName("normalizes 24:00 to 00:00")
        void normalize24() {
            Date result = LanguageUtilities.isTimeExpression("24:00");
            assertNotNull(result);
        }

        @ParameterizedTest
        @ValueSource(strings = {"hello", "abc", "25:99", "not-a-time"})
        @DisplayName("returns null for non-time strings")
        void nonTime(String input) {
            assertNull(LanguageUtilities.isTimeExpression(input));
        }
    }

    @Nested
    @DisplayName("isOrdinalNumber")
    class OrdinalNumberTests {

        @ParameterizedTest
        @CsvSource({"1st, 1", "2nd, 2", "3rd, 3", "4th, 4", "21st, 21", "100th, 100"})
        @DisplayName("extracts numeric value from ordinals")
        void validOrdinals(String input, String expected) {
            assertEquals(expected, LanguageUtilities.isOrdinalNumber(input));
        }

        @ParameterizedTest
        @ValueSource(strings = {"hello", "abc", "1", "12"})
        @DisplayName("returns null for non-ordinals")
        void nonOrdinals(String input) {
            assertNull(LanguageUtilities.isOrdinalNumber(input));
        }
    }
}
