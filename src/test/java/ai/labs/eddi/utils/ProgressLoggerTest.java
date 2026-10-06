/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProgressLoggerTest {

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final List<String> lines = new ArrayList<>();
    private final ProgressLogger progress = new ProgressLogger("conversations", 10_000L, clock::get, lines::add);

    @Test
    void writesNothingBeforeTheIntervalHasPassed() {
        for (int i = 0; i < 1000; i++) {
            progress.advance();
        }
        clock.addAndGet(9_999L);
        progress.advance();
        assertTrue(lines.isEmpty());
        assertEquals(1001, progress.processed());
    }

    @Test
    void writesOneLineAfterTheInterval() {
        progress.advance(5);
        clock.addAndGet(10_000L);
        progress.advance();
        assertEquals(1, lines.size());
        assertTrue(lines.getFirst().contains("conversations"));
        assertTrue(lines.getFirst().contains("6 processed"));
        assertTrue(lines.getFirst().contains("10s elapsed"));
    }

    @Test
    void logVolumeIsBoundedByTimeNotByItemCount() {
        for (int second = 0; second < 60; second++) {
            clock.addAndGet(1_000L);
            for (int i = 0; i < 10_000; i++) {
                progress.advance();
            }
        }
        assertEquals(6, lines.size());
    }

    @Test
    void emitIfDueNeedsNoItems() {
        clock.addAndGet(10_000L);
        progress.emitIfDue();
        assertEquals(1, lines.size());
        progress.emitIfDue();
        assertEquals(1, lines.size());
    }

    @Test
    void heartbeatCanBeClosedWithoutError() {
        try (ProgressLogger.Heartbeat heartbeat = progress.startHeartbeat()) {
            assertTrue(lines.isEmpty());
        }
    }
}
