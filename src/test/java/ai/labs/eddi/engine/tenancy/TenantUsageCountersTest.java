/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.tenancy;

import ai.labs.eddi.engine.tenancy.model.UsageSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TenantUsageCounters — increment, reset, and snapshot behavior.
 */
class TenantUsageCountersTest {

    @Test
    @DisplayName("initial counters are zero")
    void initialState() {
        var counters = new TenantUsageCounters();
        assertEquals(0, counters.getConversationsToday());
        assertEquals(0, counters.getApiCallsThisMinute());
        assertEquals(0.0, counters.getMonthlyCostUsd(), 0.001);
    }

    @Test
    @DisplayName("incrementConversations increases counter")
    void incrementConversations() {
        var counters = new TenantUsageCounters();
        counters.incrementConversations();
        counters.incrementConversations();
        assertEquals(2, counters.getConversationsToday());
    }

    @Test
    @DisplayName("incrementApiCalls increases counter")
    void incrementApiCalls() {
        var counters = new TenantUsageCounters();
        counters.incrementApiCalls();
        counters.incrementApiCalls();
        counters.incrementApiCalls();
        assertEquals(3, counters.getApiCallsThisMinute());
    }

    @Test
    @DisplayName("addCost accumulates")
    void addCost() {
        var counters = new TenantUsageCounters();
        counters.addCost(0.05);
        counters.addCost(0.10);
        assertEquals(0.15, counters.getMonthlyCostUsd(), 0.001);
    }

    @Test
    @DisplayName("resetAll clears all counters")
    void resetAll() {
        var counters = new TenantUsageCounters();
        counters.incrementConversations();
        counters.incrementApiCalls();
        counters.addCost(1.0);

        counters.resetAll();

        assertEquals(0, counters.getConversationsToday());
        assertEquals(0, counters.getApiCallsThisMinute());
        assertEquals(0.0, counters.getMonthlyCostUsd(), 0.001);
    }

    @Test
    @DisplayName("toSnapshot produces correct snapshot")
    void toSnapshot() {
        var counters = new TenantUsageCounters();
        counters.incrementConversations();
        counters.incrementApiCalls();
        counters.addCost(0.25);

        UsageSnapshot snapshot = counters.toSnapshot("tenant-1");

        assertEquals("tenant-1", snapshot.tenantId());
        assertEquals(1, snapshot.conversationsToday());
        assertEquals(1, snapshot.apiCallsThisMinute());
        assertEquals(0.25, snapshot.monthlyCostUsd(), 0.001);
        assertNotNull(snapshot.minuteWindowStart());
        assertNotNull(snapshot.dayStart());
        assertNotNull(snapshot.costMonth());
    }

    @Test
    @DisplayName("resetExpiredWindows does not reset within window")
    void resetExpiredWindows_withinWindow() {
        var counters = new TenantUsageCounters();
        counters.incrementConversations();
        counters.incrementApiCalls();
        counters.addCost(0.5);

        // Call immediately — windows haven't expired
        counters.resetExpiredWindows();

        // Within the same minute/day/month — counters should remain
        assertEquals(1, counters.getConversationsToday());
        assertEquals(1, counters.getApiCallsThisMinute());
        assertEquals(0.5, counters.getMonthlyCostUsd(), 0.001);
    }

    @Test
    @DisplayName("multiple increments and snapshot")
    void multipleIncrementsAndSnapshot() {
        var counters = new TenantUsageCounters();
        for (int i = 0; i < 100; i++) {
            counters.incrementApiCalls();
        }
        for (int i = 0; i < 10; i++) {
            counters.incrementConversations();
        }
        counters.addCost(99.99);

        UsageSnapshot snapshot = counters.toSnapshot("heavy-user");
        assertEquals(100, snapshot.apiCallsThisMinute());
        assertEquals(10, snapshot.conversationsToday());
        assertEquals(99.99, snapshot.monthlyCostUsd(), 0.001);
    }
}
