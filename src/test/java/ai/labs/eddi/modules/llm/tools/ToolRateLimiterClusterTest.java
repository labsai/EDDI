/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.ClusterPresence;
import ai.labs.eddi.engine.cluster.DownSharedKv;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.InMemorySharedKv;
import ai.labs.eddi.engine.cluster.NatsSharedStateFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cluster mode ({@code eddi.messaging.type=nats}) of {@link ToolRateLimiter}:
 * the shared KV buckets, the deployment-wide layer on top of them, and the
 * degraded behaviour while NATS is unreachable.
 */
class ToolRateLimiterClusterTest {

    private SimpleMeterRegistry registry;
    private ClusterConfig clusterConfig;
    private ClusterPresence presence;
    private Instance<ClusterPresence> presenceInstance;
    private ISharedKv kv;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        clusterConfig = mock(ClusterConfig.class);
        when(clusterConfig.isNats()).thenReturn(true);
        when(clusterConfig.degradedRateLimits()).thenReturn(ClusterConfig.LOCAL_SHARE);
        presence = mock(ClusterPresence.class);
        when(presence.lastKnownMembers()).thenReturn(2);
        @SuppressWarnings("unchecked")
        Instance<ClusterPresence> p = mock(Instance.class);
        presenceInstance = p;
        when(presenceInstance.isResolvable()).thenReturn(true);
        when(presenceInstance.get()).thenReturn(presence);
        kv = new InMemorySharedKv("RATELIMIT", Duration.ofMinutes(2));
    }

    private ToolRateLimiter limiter(ISharedKv bucket, boolean globalEnabled, int globalLimit) {
        ToolRateLimiter limiter = new ToolRateLimiter();
        limiter.meterRegistry = registry;
        limiter.clusterConfig = clusterConfig;
        NatsSharedStateFactory factory = mock(NatsSharedStateFactory.class);
        when(factory.bucket(any())).thenReturn(bucket);
        @SuppressWarnings("unchecked")
        Instance<NatsSharedStateFactory> shared = mock(Instance.class);
        when(shared.get()).thenReturn(factory);
        limiter.natsSharedState = shared;
        limiter.presence = presenceInstance;
        limiter.globalLimitEnabled = globalEnabled;
        limiter.globalLimit = globalLimit;
        return limiter;
    }

    private double decisions(String scope, String outcome) {
        var counter = registry.find("eddi.cluster.ratelimit.decisions").tag("scope", scope).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void theLimitHoldsAcrossReplicasSharingTheBucket() {
        ToolRateLimiter nodeA = limiter(kv, false, 1000);
        ToolRateLimiter nodeB = limiter(kv, false, 1000);

        assertTrue(nodeA.tryAcquire("conv-1", "search", 2));
        assertTrue(nodeB.tryAcquire("conv-1", "search", 2));
        assertFalse(nodeA.tryAcquire("conv-1", "search", 2), "two calls per minute across the cluster, not per replica");
        assertTrue(nodeB.tryAcquire("conv-2", "search", 2), "another conversation has its own bucket");

        assertEquals(3, decisions("scoped", "allowed"));
        assertEquals(1, decisions("scoped", "denied"));
        assertEquals(1, registry.find("eddi.tool.ratelimit.denied").tag("tool", "search").counter().count());
    }

    @Test
    void theGlobalLayerRefusesAndGivesTheConversationItsTokenBack() {
        ToolRateLimiter withGlobal = limiter(kv, true, 1);
        assertTrue(withGlobal.tryAcquire("conv-1", "search", 2));
        assertFalse(withGlobal.tryAcquire("conv-1", "search", 2), "the deployment-wide allowance of one is spent");
        assertEquals(1, decisions("global", "denied"));

        // The refused call did not cost the conversation quota: one of its two
        // tokens is left, as a limiter without the global layer shows.
        ToolRateLimiter scopedOnly = limiter(kv, false, 1000);
        assertTrue(scopedOnly.tryAcquire("conv-1", "search", 2));
        assertFalse(scopedOnly.tryAcquire("conv-1", "search", 2));
    }

    @Test
    void whileNatsIsDownThePerConversationLimitIsEnforcedLocallyInFull() {
        ToolRateLimiter degraded = limiter(new DownSharedKv(), false, 1000);
        assertTrue(degraded.tryAcquire("conv-1", "search", 2));
        assertTrue(degraded.tryAcquire("conv-1", "search", 2));
        assertFalse(degraded.tryAcquire("conv-1", "search", 2));
        assertEquals(3, decisions("scoped", "degraded"));
        assertEquals(3, registry.find("eddi.cluster.degraded.decisions").tag("action", "local-share").counter().count());
    }

    @Test
    void whileNatsIsDownTheGlobalLimitIsSplitAcrossTheLastKnownMembers() {
        ToolRateLimiter degraded = limiter(new DownSharedKv(), true, 4);
        // 4 per minute across 2 members: this node may admit 2.
        assertTrue(degraded.tryAcquire("conv-1", "search", 100));
        assertTrue(degraded.tryAcquire("conv-2", "search", 100));
        assertFalse(degraded.tryAcquire("conv-3", "search", 100));
    }

    @Test
    void withoutPresenceTheNodeAssumesItIsAlone() {
        when(presenceInstance.isResolvable()).thenReturn(false);
        ToolRateLimiter degraded = limiter(new DownSharedKv(), true, 2);
        assertTrue(degraded.tryAcquire("conv-1", "search", 100));
        assertTrue(degraded.tryAcquire("conv-2", "search", 100));
        assertFalse(degraded.tryAcquire("conv-3", "search", 100));
    }

    @Test
    void theRejectPolicyRefusesEveryCallWhileNatsIsDown() {
        when(clusterConfig.degradedRateLimits()).thenReturn(ClusterConfig.REJECT);
        ToolRateLimiter degraded = limiter(new DownSharedKv(), false, 1000);
        assertFalse(degraded.tryAcquire("conv-1", "search", 100));
        assertFalse(degraded.refusedByGlobalLimit("search"), "the global check is off");
        assertEquals(1, registry.find("eddi.cluster.degraded.decisions").tag("action", "reject").counter().count());
    }

    @Test
    void refusedByGlobalLimitUsesTheSharedBucket() {
        ToolRateLimiter nodeA = limiter(kv, true, 1);
        ToolRateLimiter nodeB = limiter(kv, true, 1);
        assertFalse(nodeA.refusedByGlobalLimit("search"));
        assertTrue(nodeB.refusedByGlobalLimit("search"), "the one call per minute was taken on the other node");
    }

    @Test
    void refusedByGlobalLimitFallsBackToTheLocalShareOrRejects() {
        ToolRateLimiter degraded = limiter(new DownSharedKv(), true, 2);
        assertFalse(degraded.refusedByGlobalLimit("search"), "a share of one call per member");
        assertTrue(degraded.refusedByGlobalLimit("search"));

        when(clusterConfig.degradedRateLimits()).thenReturn(ClusterConfig.REJECT);
        assertTrue(limiter(new DownSharedKv(), true, 1000).refusedByGlobalLimit("other"));
    }

    @Test
    void localModeTreatsBlankAndNullConversationsAsOneUnscopedBucket() {
        ToolRateLimiter local = new ToolRateLimiter();
        local.meterRegistry = registry;
        assertTrue(local.tryAcquire("  ", "tool", 1));
        assertFalse(local.tryAcquire(null, "tool", 1));
        assertFalse(local.tryAcquire("tool", 1));
    }

    @Test
    void resetTimeIsOneWindowAwayForAnUnknownToolAndForABlockedTool() {
        ToolRateLimiter local = new ToolRateLimiter();
        local.meterRegistry = registry;
        long before = System.currentTimeMillis();
        long unknown = local.getResetTimeMs("never-called");
        assertTrue(unknown >= before + ToolRateLimiter.WINDOW_MS);

        assertFalse(local.tryAcquire("c", "blocked", 0));
        assertTrue(local.getResetTimeMs("blocked") >= before + ToolRateLimiter.WINDOW_MS);
        assertEquals(0, local.getInfo("blocked").limit);
    }

    @Test
    void validationAcceptsANullPerToolLimitAndNamesANegativeOne() {
        HashMap<String, Integer> limits = new HashMap<>();
        limits.put("a", null);
        limits.put("b", 5);
        ToolRateLimiter.validateLimits(null, limits, "tasks[0]");
        var e = assertThrows(IllegalArgumentException.class, () -> ToolRateLimiter.validateLimits(3, Map.of("c", -1), "tasks[0]"));
        assertTrue(e.getMessage().startsWith("tasks[0].toolRateLimits.c is -1"), e.getMessage());
    }

    @Test
    void anUnchangedLimitKeepsTheBucketState() {
        var bucket = new ToolRateLimiter.RateLimitBucket(3);
        assertTrue(bucket.tryAcquire());
        bucket.updateLimit(3);
        assertEquals(2, bucket.getRemaining());
        bucket.updateLimit(-4);
        assertEquals(0, bucket.getLimit());
        assertEquals(ToolRateLimiter.WINDOW_MS, bucket.getRetryAfterMs());
    }
}
