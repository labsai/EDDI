/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.modules.llm.impl.IProviderCooldownStore;
import ai.labs.eddi.modules.llm.impl.ProviderCooldowns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Drive's quota counted in units per connection, and its shared cooldown. */
class DriveQuotaTest {

    private final Instant start = Instant.parse("2026-11-23T10:00:00Z");

    @Test
    @DisplayName("calls are charged in units: a download costs twice a listing, and a spent minute waits")
    void unitsAreCharged() {
        DriveQuota quota = DriveQuota.local(600);
        assertEquals(Duration.ZERO, quota.reserve("drive-sa", DriveQuota.DOWNLOAD_UNITS, start));
        assertEquals(Duration.ZERO, quota.reserve("drive-sa", DriveQuota.DOWNLOAD_UNITS, start));
        assertEquals(Duration.ZERO, quota.reserve("drive-sa", DriveQuota.LIST_UNITS, start));
        assertEquals(Duration.ZERO, quota.reserve("drive-sa", DriveQuota.LIST_UNITS, start));
        // 600 spent: the next 200 refill in 20 s at 10 units a second.
        assertEquals(Duration.ofSeconds(20), quota.reserve("drive-sa", DriveQuota.DOWNLOAD_UNITS, start));
        assertEquals(Duration.ZERO, quota.reserve("other-connection", DriveQuota.DOWNLOAD_UNITS, start),
                "each connection has its own bucket");
        assertEquals(Duration.ZERO, quota.reserve("drive-sa", DriveQuota.DOWNLOAD_UNITS, start.plusSeconds(20)));
    }

    @Test
    @DisplayName("no limit configured charges nothing")
    void unlimited() {
        DriveQuota quota = DriveQuota.unlimited();
        for (int i = 0; i < 10_000; i++) {
            assertEquals(Duration.ZERO, quota.reserve("drive-sa", DriveQuota.DOWNLOAD_UNITS, start));
        }
    }

    @Test
    @DisplayName("a throttle one node hears is the cooldown every node reads")
    void throttlesAreShared() {
        Map<String, IProviderCooldownStore.Cooldown> rows = new HashMap<>();
        IProviderCooldownStore store = new IProviderCooldownStore() {
            @Override
            public Optional<Cooldown> read(String throttleKey) {
                return Optional.ofNullable(rows.get(throttleKey));
            }

            @Override
            public void extend(String throttleKey, Instant until, boolean daily) {
                rows.put(throttleKey, new Cooldown(throttleKey, until, daily));
            }
        };
        Instant now = Instant.now();
        new DriveQuota(250_000, new ProviderCooldowns(store)).recordThrottle("drive-sa", now.plusSeconds(90));

        DriveQuota otherNode = new DriveQuota(250_000, new ProviderCooldowns(store));
        assertTrue(otherNode.cooldownUntil("drive-sa").isPresent());
        assertTrue(otherNode.cooldownUntil("another-connection").isEmpty());
    }
}
