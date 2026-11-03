/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import ai.labs.eddi.engine.cluster.events.ClusterEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B5: the user id of {@code gdpr.user-erased} travels sealed when a signing key
 * is set, and a re-seeded audit-position counter is counted.
 */
@DisplayName("AuditClusterSupport — sealed user ids and counted re-seeds")
class ClusterReplayAndPseudonymTest {

    private static final String KEY = "0123456789abcdef0123456789abcdef-seal-key";

    private static ClusterMessageSigner signer(String key) {
        return new ClusterMessageSigner(ClusterSigningConfig.of(key, List.of(), false, Duration.ofSeconds(60)), new SimpleMeterRegistry());
    }

    @SuppressWarnings("unchecked")
    private static Instance<ClusterMessageSigner> instance(ClusterMessageSigner value) {
        Instance<ClusterMessageSigner> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(value);
        return instance;
    }

    @Test
    @DisplayName("gdpr.user-erased carries a sealed hash that only a node with the key opens; a plain one is still read")
    void userErasedIsSealed() {
        ClusterMessageSigner signer = signer(KEY);
        AuditClusterSupport support = new AuditClusterSupport(new InMemorySharedKv("A", Duration.ofDays(1)), new SimpleMeterRegistry());
        support.signer = instance(signer);
        String sealed = signer.seal(KvKeys.sha256("alice@example.com"));
        ClusterEvent event = new ClusterEvent(1, "e", ClusterEvent.GDPR_USER_ERASED, "n2", "b", 0L, null, Map.of("userIdSealed", sealed));

        assertEquals(KvKeys.sha256("alice@example.com"), support.userIdHashOf(event));

        support.signer = instance(signer("a-different-key-that-is-long-enough-000"));
        assertNull(support.userIdHashOf(event), "another key cannot open it");
        ClusterEvent plain = new ClusterEvent(1, "e", ClusterEvent.GDPR_USER_ERASED, "n2", "b", 0L, null,
                Map.of("userIdHash", KvKeys.sha256("bob@example.com")));
        assertEquals(KvKeys.sha256("bob@example.com"), support.userIdHashOf(plain), "an older node's plain hash is still read");
    }

    @Test
    @DisplayName("a re-seeded audit-position counter is counted")
    void reseedIsCounted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InMemorySharedKv kv = new InMemorySharedKv("AUDIT_SEQ", Duration.ofDays(1));
        AuditClusterSupport support = new AuditClusterSupport(kv, registry);
        List<Long> positions = new ArrayList<>();
        support.nextSequence("c1", () -> 41L).ifPresent(positions::add);
        support.nextSequence("c1", () -> 0L).ifPresent(positions::add);
        kv.delete("s.c1"); // the counter is lost
        support.nextSequence("c1", () -> 42L).ifPresent(positions::add);

        assertEquals(List.of(41L, 42L, 42L), positions, "the store's highest position re-seeds it; the duplicate is now attributable");
        assertEquals(2.0, registry.counter("eddi.cluster.audit.sequence", "outcome", "seeded").count());
    }
}
