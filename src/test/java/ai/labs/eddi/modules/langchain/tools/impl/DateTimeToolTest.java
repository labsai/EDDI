package ai.labs.eddi.modules.langchain.tools.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DateTimeToolTest {

    private DateTimeTool dateTimeTool;

    @BeforeEach
    void setUp() {
        dateTimeTool = new DateTimeTool();
    }

    @Test
    void testGetCurrentDateTime_UTC() {
        String result = dateTimeTool.getCurrentDateTime("UTC");
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("UTC") || result.contains("Z"));
    }

    @Test
    void testGetCurrentDateTime_NewYork() {
        String result = dateTimeTool.getCurrentDateTime("America/New_York");
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("America") || result.contains("EST") || result.contains("EDT"));
    }

    @Test
    void testGetCurrentDateTime_Tokyo() {
        String result = dateTimeTool.getCurrentDateTime("Asia/Tokyo");
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("JST") || result.contains("Asia"));
    }

    @Test
    void testGetCurrentDateTime_London() {
        String result = dateTimeTool.getCurrentDateTime("Europe/London");
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testGetCurrentDateTime_InvalidTimezone() {
        String result = dateTimeTool.getCurrentDateTime("Invalid/Timezone");
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("Invalid timezone"));
    }

    @Test
    void testConvertTimezone_UTCToNewYork() {
        String result = dateTimeTool.convertTimezone(
                "2025-11-03T10:00:00",
                "UTC",
                "America/New_York"
        );
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testConvertTimezone_NewYorkToTokyo() {
        String result = dateTimeTool.convertTimezone(
                "2025-11-03T10:00:00",
                "America/New_York",
                "Asia/Tokyo"
        );
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
    }

    @Test
    void testConvertTimezone_InvalidSourceTimezone() {
        String result = dateTimeTool.convertTimezone(
                "2025-11-03T10:00:00",
                "Invalid/Zone",
                "UTC"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testConvertTimezone_InvalidTargetTimezone() {
        String result = dateTimeTool.convertTimezone(
                "2025-11-03T10:00:00",
                "UTC",
                "Invalid/Zone"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testConvertTimezone_InvalidDateFormat() {
        String result = dateTimeTool.convertTimezone(
                "not-a-date",
                "UTC",
                "America/New_York"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculateDateDifference_Days() {
        String result = dateTimeTool.calculateDateDifference(
                "2025-11-01T10:00:00",
                "2025-11-03T10:00:00",
                "days"
        );
        assertNotNull(result);
        assertTrue(result.contains("2 days") || result.contains("2.0 days"));
    }

    @Test
    void testCalculateDateDifference_Hours() {
        String result = dateTimeTool.calculateDateDifference(
                "2025-11-03T10:00:00",
                "2025-11-03T15:00:00",
                "hours"
        );
        assertNotNull(result);
        assertTrue(result.contains("5 hours"));
    }

    @Test
    void testCalculateDateDifference_Minutes() {
        String result = dateTimeTool.calculateDateDifference(
                "2025-11-03T10:00:00",
                "2025-11-03T10:30:00",
                "minutes"
        );
        assertNotNull(result);
        assertTrue(result.contains("30 minutes"));
    }

    @Test
    void testCalculateDateDifference_Seconds() {
        String result = dateTimeTool.calculateDateDifference(
                "2025-11-03T10:00:00",
                "2025-11-03T10:00:45",
                "seconds"
        );
        assertNotNull(result);
        assertTrue(result.contains("45 seconds"));
    }

    @Test
    void testCalculateDateDifference_NegativeDifference() {
        String result = dateTimeTool.calculateDateDifference(
                "2025-11-03T10:00:00",
                "2025-11-01T10:00:00",
                "days"
        );
        assertNotNull(result);
        assertTrue(result.contains("-2 days") || result.contains("-2.0 days"));
    }

    @Test
    void testCalculateDateDifference_InvalidUnit() {
        String result = dateTimeTool.calculateDateDifference(
                "2025-11-03T10:00:00",
                "2025-11-03T11:00:00",
                "invalid"
        );
        assertTrue(result.startsWith("Error"));
        assertTrue(result.contains("Invalid unit"));
    }

    @Test
    void testCalculateDateDifference_InvalidStartDate() {
        String result = dateTimeTool.calculateDateDifference(
                "invalid-date",
                "2025-11-03T10:00:00",
                "days"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculateDateDifference_InvalidEndDate() {
        String result = dateTimeTool.calculateDateDifference(
                "2025-11-03T10:00:00",
                "invalid-date",
                "days"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testCalculateDateDifference_SameDateTime() {
        String result = dateTimeTool.calculateDateDifference(
                "2025-11-03T10:00:00",
                "2025-11-03T10:00:00",
                "seconds"
        );
        assertNotNull(result);
        assertTrue(result.contains("0 seconds"));
    }

    @Test
    void testAddTime_Days() {
        String result = dateTimeTool.addTime(
                "2025-11-03T10:00:00",
                5,
                "days",
                "UTC"
        );
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("2025-11-08"));
    }

    @Test
    void testAddTime_Hours() {
        String result = dateTimeTool.addTime(
                "2025-11-03T10:00:00",
                2,
                "hours",
                "UTC"
        );
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("12:00"));
    }

    @Test
    void testAddTime_NegativeAmount() {
        String result = dateTimeTool.addTime(
                "2025-11-03T10:00:00",
                -1,
                "days",
                "UTC"
        );
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("2025-11-02"));
    }

    @Test
    void testAddTime_InvalidUnit() {
        String result = dateTimeTool.addTime(
                "2025-11-03T10:00:00",
                1,
                "invalid",
                "UTC"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testAddTime_InvalidDateFormat() {
        String result = dateTimeTool.addTime(
                "invalid-date",
                1,
                "days",
                "UTC"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testAddTime_InvalidTimezone() {
        String result = dateTimeTool.addTime(
                "2025-11-03T10:00:00",
                1,
                "days",
                "Invalid/Zone"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testFormatDateTime_StandardFormat() {
        String result = dateTimeTool.formatDateTime(
                "2025-11-03T10:30:00",
                "yyyy-MM-dd",
                "UTC"
        );
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("2025-11-03"));
    }

    @Test
    void testFormatDateTime_CustomFormat() {
        String result = dateTimeTool.formatDateTime(
                "2025-11-03T10:30:00",
                "dd/MM/yyyy HH:mm",
                "UTC"
        );
        assertNotNull(result);
        assertFalse(result.startsWith("Error"));
        assertTrue(result.contains("03/11/2025"));
    }

    @Test
    void testFormatDateTime_InvalidDateFormat() {
        String result = dateTimeTool.formatDateTime(
                "invalid-date",
                "yyyy-MM-dd",
                "UTC"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testFormatDateTime_InvalidPattern() {
        String result = dateTimeTool.formatDateTime(
                "2025-11-03T10:30:00",
                "invalid-pattern",
                "UTC"
        );
        assertTrue(result.startsWith("Error"));
    }

    @Test
    void testFormatDateTime_InvalidTimezone() {
        String result = dateTimeTool.formatDateTime(
                "2025-11-03T10:30:00",
                "yyyy-MM-dd",
                "Invalid/Zone"
        );
        assertTrue(result.startsWith("Error"));
    }
}

