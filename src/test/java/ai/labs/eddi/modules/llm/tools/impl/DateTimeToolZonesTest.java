/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.modules.llm.tools.NeverCached;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offsets and zones in the input are honoured, and a local time that does not
 * exist is reported rather than shifted (review P3).
 */
class DateTimeToolZonesTest {

    private final DateTimeTool tool = new DateTimeTool();

    @Test
    void utcInstantIsNotTreatedAsLocal() {
        // 10:30Z is 11:30 in Vienna in winter; as "local in UTC" it was 10:30 UTC
        // either way,
        // but with fromTimezone=Asia/Tokyo the old code read it as 10:30 Tokyo time.
        assertEquals("2025-11-03 11:30:00 CET", tool.convertTimezone("2025-11-03T10:30:00Z", "Asia/Tokyo", "Europe/Vienna"));
    }

    @Test
    void numericOffsetWins() {
        assertEquals("2025-11-03 03:30:00 EST", tool.convertTimezone("2025-11-03T10:30:00+02:00", "Asia/Tokyo", "America/New_York"));
    }

    @Test
    void localInputStillUsesFromTimezone() {
        assertEquals("2025-11-03 12:30:00 CET", tool.convertTimezone("2025-11-03T20:30:00", "Asia/Tokyo", "Europe/Vienna"));
    }

    @Test
    void nonExistentLocalTimeIsReportedNotShifted() {
        // Europe/Vienna springs forward 2025-03-30 02:00 -> 03:00
        String result = tool.convertTimezone("2025-03-30T02:30:00", "Europe/Vienna", "UTC");
        assertTrue(result.startsWith("Error:"), result);
        assertTrue(result.contains("does not exist"), result);

        assertTrue(tool.formatDateTime("2025-03-30T02:30:00", "HH:mm", "Europe/Vienna").startsWith("Error:"));
    }

    @Test
    void addTimeLandingInTheGapIsReported() {
        String result = tool.addTime("2025-03-29T02:30:00", 1, "days", "Europe/Vienna");
        assertTrue(result.startsWith("Error:") && result.contains("does not exist"), result);
    }

    @Test
    void addTimeHonoursAnOffsetInTheInput() {
        // 10:30Z in Vienna (CET, +1) is 11:30; plus one hour -> 12:30
        assertEquals("2025-11-03 12:30:00 CET", tool.addTime("2025-11-03T10:30:00Z", 1, "hours", "Europe/Vienna"));
    }

    @Test
    void anHourAcrossSpringForwardIsInstantArithmetic() {
        // Europe/Vienna: 2025-03-30 02:00 CET -> 03:00 CEST. 01:30 + 1h is 03:30 CEST.
        assertEquals("2025-03-30 03:30:00 CEST", tool.addTime("2025-03-30T01:30:00", 1, "hours", "Europe/Vienna"));
        assertEquals("2025-03-30 03:15:00 CEST", tool.addTime("2025-03-30T01:45:00", 30, "minutes", "Europe/Vienna"));
    }

    @Test
    void anHourAcrossFallBackCountsTheRepeatedHour() {
        // Europe/Vienna: 2025-10-26 03:00 CEST -> 02:00 CET. 01:30 CEST + 1h is 02:30
        // CEST,
        // + 2h is 02:30 CET — the repeated hour is real time.
        assertEquals("2025-10-26 02:30:00 CEST", tool.addTime("2025-10-26T01:30:00", 1, "hours", "Europe/Vienna"));
        assertEquals("2025-10-26 02:30:00 CET", tool.addTime("2025-10-26T01:30:00", 2, "hours", "Europe/Vienna"));
        // An ambiguous local input takes the earlier offset (CEST), so + 1h is 02:30
        // CET.
        assertEquals("2025-10-26 02:30:00 CET", tool.addTime("2025-10-26T02:30:00", 1, "hours", "Europe/Vienna"));
    }

    @Test
    void anInputCarryingTheLaterOffsetOfTheRepeatedHourKeepsIt() {
        assertEquals("2025-10-26 03:30:00 CET", tool.addTime("2025-10-26T02:30:00+01:00", 1, "hours", "Europe/Vienna"));
        assertEquals("2025-10-26 02:30:00 CET", tool.addTime("2025-10-26T02:30:00+01:00", 0, "days", "Europe/Vienna"));
    }

    @Test
    void aDayIntoTheRepeatedHourKeepsTheStartsOffset() {
        assertEquals("2025-10-26 02:30:00 CEST", tool.addTime("2025-10-25T02:30:00", 1, "days", "Europe/Vienna"));
        assertEquals("2025-10-26 02:30:00 CET", tool.addTime("2025-10-27T02:30:00", -1, "days", "Europe/Vienna"));
    }

    @Test
    void listTimezonesReportsTheCurrentTimeAndIsNeverCached() throws Exception {
        assertTrue(DateTimeTool.class.getMethod("listTimezones").isAnnotationPresent(NeverCached.class));
    }

    @Test
    void differenceBetweenInstantsWithOffsets() {
        assertEquals("Difference: 2 hours", tool.calculateDateDifference("2025-11-03T10:00:00Z", "2025-11-03T14:00:00+02:00", "hours"));
        assertTrue(tool.calculateDateDifference("2025-11-03T10:00:00Z", "2025-11-03T14:00:00", "hours").startsWith("Error:"));
        assertEquals("Difference: 4 hours", tool.calculateDateDifference("2025-11-03T10:00:00", "2025-11-03T14:00:00", "hours"));
    }

    @Test
    void currentTimeIsNeverCachedButPureConversionsAre() throws Exception {
        assertTrue(DateTimeTool.class.getMethod("getCurrentDateTime", String.class).isAnnotationPresent(NeverCached.class));
        assertFalse(DateTimeTool.class.getMethod("convertTimezone", String.class, String.class, String.class)
                .isAnnotationPresent(NeverCached.class));
    }
}
