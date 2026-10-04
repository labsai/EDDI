/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.nats.client.api.KeyValueConfiguration;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("NatsSharedStateFactory leader-only reads")
class NatsSharedStateFactoryLeaderReadsTest {

    private static KeyValueConfiguration bucket() {
        return KeyValueConfiguration.builder().name("T_LEASES").ttl(Duration.ofSeconds(20)).storageType(StorageType.File).replicas(3)
                .maxHistoryPerKey(1).build();
    }

    @Test
    @DisplayName("jnats makes a bucket direct-get capable by default — which lets a lagging follower answer a read")
    void jnatsDefaultIsDirect() {
        assertTrue(bucket().getBackingConfig().getAllowDirect());
    }

    @Test
    @DisplayName("the backing stream of every bucket has direct get off and keeps the rest of the bucket's configuration")
    void leaderReadsTurnsDirectOff() {
        StreamConfiguration config = NatsSharedStateFactory.leaderReads(bucket());
        assertFalse(config.getAllowDirect());
        assertEquals("KV_T_LEASES", config.getName());
        assertEquals(3, config.getReplicas());
        assertEquals(Duration.ofSeconds(20), config.getMaxAge());
        assertEquals(1, config.getMaxMsgsPerSubject());
    }
}
