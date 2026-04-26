/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for {@link DateTimeTool} — covers all switch branches for
 * addTime, calculateDateDifference, error handling, and listTimezones.
 */
class DateTimeToolExtendedTest {

    private DateTimeTool tool;
    private static final String SAMPLE_DT = "2025-06-15T10:30:00";

    @BeforeEach
    void setUp() {
        tool = new DateTimeTool();
    }

    @Nested
    @DisplayName("addTime — all unit branches")
    class AddTime {

        @Test
        @DisplayName("adds years")
        void addYears() {
            String result = tool.addTime(SAMPLE_DT, 2, "years", "UTC");
            assertTrue(result.contains("2027"));
        }

        @Test
        @DisplayName("adds months")
        void addMonths() {
            String result = tool.addTime(SAMPLE_DT, 3, "months", "UTC");
            assertTrue(result.contains("09") || result.contains("Sep")); // September
        }

        @Test
        @DisplayName("adds weeks")
        void addWeeks() {
            String result = tool.addTime(SAMPLE_DT, 1, "weeks", "UTC");
            assertTrue(result.contains("22")); // June 15 + 7 = June 22
        }

        @Test
        @DisplayName("adds days")
        void addDays() {
            String result = tool.addTime(SAMPLE_DT, 5, "days", "UTC");
            assertTrue(result.contains("20")); // June 15 + 5 = June 20
        }

        @Test
        @DisplayName("adds hours")
        void addHours() {
            String result = tool.addTime(SAMPLE_DT, 3, "hours", "UTC");
            assertTrue(result.contains("13:30"));
        }

        @Test
        @DisplayName("adds minutes")
        void addMinutes() {
            String result = tool.addTime(SAMPLE_DT, 45, "minutes", "UTC");
            assertTrue(result.contains("11:15"));
        }

        @Test
        @DisplayName("adds seconds")
        void addSeconds() {
            String result = tool.addTime(SAMPLE_DT, 30, "seconds", "UTC");
            assertTrue(result.contains("10:30:30"));
        }

        @Test
        @DisplayName("subtracts time with negative amount")
        void subtractTime() {
            String result = tool.addTime(SAMPLE_DT, -2, "days", "UTC");
            assertTrue(result.contains("13")); // June 15 - 2 = June 13
        }

        @Test
        @DisplayName("returns error for invalid unit")
        void invalidUnit() {
            String result = tool.addTime(SAMPLE_DT, 1, "fortnights", "UTC");
            assertTrue(result.startsWith("Error:"));
        }

        @Test
        @DisplayName("returns error for invalid timezone")
        void invalidTimezone() {
            String result = tool.addTime(SAMPLE_DT, 1, "days", "Invalid/Zone");
            assertTrue(result.startsWith("Error:"));
        }

        @Test
        @DisplayName("returns error for invalid dateTime format")
        void invalidDateTime() {
            String result = tool.addTime("not-a-date", 1, "days", "UTC");
            assertTrue(result.startsWith("Error:"));
        }
    }

    @Nested
    @DisplayName("calculateDateDifference — all unit branches")
    class CalcDiff {

        private static final String START = "2025-06-15T10:00:00";
        private static final String END = "2025-06-17T14:30:00";

        @Test
        @DisplayName("calculates days")
        void daysDiff() {
            String result = tool.calculateDateDifference(START, END, "days");
            assertTrue(result.contains("2") && result.contains("days"));
        }

        @Test
        @DisplayName("calculates hours")
        void hoursDiff() {
            String result = tool.calculateDateDifference(START, END, "hours");
            assertTrue(result.contains("52") && result.contains("hours"));
        }

        @Test
        @DisplayName("calculates minutes")
        void minutesDiff() {
            String result = tool.calculateDateDifference(START, END, "minutes");
            assertTrue(result.contains("3150") && result.contains("minutes"));
        }

        @Test
        @DisplayName("calculates seconds")
        void secondsDiff() {
            String result = tool.calculateDateDifference(START, END, "seconds");
            assertTrue(result.contains("189000") && result.contains("seconds"));
        }

        @Test
        @DisplayName("returns error for invalid unit")
        void invalidUnit() {
            String result = tool.calculateDateDifference(START, END, "centuries");
            assertTrue(result.startsWith("Error:"));
        }

        @Test
        @DisplayName("returns error for invalid date format")
        void invalidDate() {
            String result = tool.calculateDateDifference("bad", END, "days");
            assertTrue(result.startsWith("Error:"));
        }
    }

    @Nested
    @DisplayName("convertTimezone")
    class ConvertTimezone {

        @Test
        @DisplayName("converts between timezones")
        void convertsTimezones() {
            String result = tool.convertTimezone(SAMPLE_DT, "America/New_York", "Europe/London");
            assertNotNull(result);
            assertFalse(result.startsWith("Error:"));
        }

        @Test
        @DisplayName("returns error for invalid timezone")
        void invalidTimezone() {
            String result = tool.convertTimezone(SAMPLE_DT, "Invalid/Zone", "UTC");
            assertTrue(result.startsWith("Error:"));
        }
    }

    @Nested
    @DisplayName("formatDateTime")
    class FormatDateTime {

        @Test
        @DisplayName("formats with custom pattern")
        void formatsCustomPattern() {
            String result = tool.formatDateTime(SAMPLE_DT, "dd/MM/yyyy", "UTC");
            assertEquals("15/06/2025", result);
        }

        @Test
        @DisplayName("returns error for invalid timezone in format")
        void invalidTimezoneInFormat() {
            String result = tool.formatDateTime(SAMPLE_DT, "dd/MM/yyyy", "Not/Real/Zone");
            assertTrue(result.startsWith("Error:"));
        }

        @Test
        @DisplayName("returns error for invalid date")
        void invalidDate() {
            String result = tool.formatDateTime("not-a-date", "dd/MM/yyyy", "UTC");
            assertTrue(result.startsWith("Error:"));
        }
    }

    @Nested
    @DisplayName("getCurrentDateTime")
    class GetCurrentDateTime {

        @Test
        @DisplayName("returns error for invalid timezone")
        void invalidTimezone() {
            String result = tool.getCurrentDateTime("Nowhere/Invalid");
            assertTrue(result.startsWith("Error:"));
        }
    }

    @Nested
    @DisplayName("listTimezones")
    class ListTimezones {

        @Test
        @DisplayName("returns all major timezones")
        void listsMajorTimezones() {
            String result = tool.listTimezones();
            assertTrue(result.contains("UTC"));
            assertTrue(result.contains("America/New_York"));
            assertTrue(result.contains("Europe/Berlin"));
            assertTrue(result.contains("Asia/Tokyo"));
            assertTrue(result.contains("Australia/Sydney"));
        }
    }
}
