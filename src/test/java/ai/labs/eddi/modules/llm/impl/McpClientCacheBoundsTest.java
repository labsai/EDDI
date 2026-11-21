/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/**
 * The MCP client cache is bounded, closes what it evicts, and builds a client
 * outside any lock.
 * <p>
 * {@code buildClient} is overridden, so no MCP server and no socket are
 * involved: each "client" is a mock that records when, and on which thread, it
 * was closed.
 */
@DisplayName("McpToolProviderManager — bounded client cache")
class McpClientCacheBoundsTest {

    private static final CallerIdentityContext NO_CALLER = new CallerIdentityContext(null, null);

    private final ExecutorService pool = Executors.newCachedThreadPool();

    /** URL of every client closed so far, with the thread that closed it. */
    private final Map<String, Thread> closed = new ConcurrentHashMap<>();
    private final AtomicInteger builds = new AtomicInteger();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private McpToolProviderManager manager(int maxSize, Function<McpServerConfig, McpClient> builder) {
        return new McpToolProviderManager(mock(GlobalVariableResolver.class), mock(SecretResolver.class),
                new CallerIdentityResolver(NO_CALLER, true), NO_CALLER, false, McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS,
                McpToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES, McpToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES_PER_SERVER,
                McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS, maxSize,
                McpToolProviderManager.DEFAULT_CLIENT_CACHE_EXPIRE_AFTER_ACCESS_MILLIS, null) {
            @Override
            McpClient buildClient(McpServerConfig config) {
                builds.incrementAndGet();
                return builder.apply(config);
            }
        };
    }

    private McpClient recordingClient(String url) {
        McpClient client = mock(McpClient.class);
        try {
            doAnswer(invocation -> {
                closed.put(url, Thread.currentThread());
                return null;
            }).when(client).close();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return client;
    }

    private static McpServerConfig config(String url) {
        var config = new McpServerConfig();
        config.setName(url);
        config.setUrl(url);
        return config;
    }

    private void awaitClosed(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (closed.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("over capacity, an entry is evicted and its client closed off the caller's thread")
    void overCapacityEvictsAndCloses() throws Exception {
        var manager = manager(2, c -> recordingClient(c.getUrl()));
        List<String> urls = List.of("http://a.example/mcp", "http://b.example/mcp", "http://c.example/mcp");
        Map<String, McpClient> built = new ConcurrentHashMap<>();
        for (String url : urls) {
            built.put(url, manager.getOrCreateClient(config(url)));
        }

        manager.runCacheMaintenance();
        awaitClosed(1);

        assertEquals(2, manager.getActiveConnectionCount(), "the cache must hold no more than its maximum size");
        assertEquals(1, closed.size(), "exactly the evicted client is closed: " + closed.keySet());
        String evicted = closed.keySet().iterator().next();
        assertNotEquals(Thread.currentThread(), closed.get(evicted), "an evicted client is closed on Caffeine's executor");

        // The survivors are still served from the cache — no rebuild, same instance.
        int buildsBefore = builds.get();
        for (String url : urls) {
            if (!url.equals(evicted)) {
                assertSame(built.get(url), manager.getOrCreateClient(config(url)));
            }
        }
        assertEquals(buildsBefore, builds.get());

        // The evicted one is rebuilt on next use rather than handed out closed.
        assertNotSame(built.get(evicted), manager.getOrCreateClient(config(evicted)));
        assertEquals(buildsBefore + 1, builds.get());
    }

    @Test
    @DisplayName("concurrent first use of one key builds one client, shared by every caller")
    void concurrentFirstUseBuildsOnce() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var manager = manager(16, c -> {
            entered.countDown();
            await(release);
            return recordingClient(c.getUrl());
        });
        var config = config("http://shared.example/mcp");

        List<Future<McpClient>> callers = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            callers.add(pool.submit(() -> manager.getOrCreateClient(config)));
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        Thread.sleep(100); // let the other callers reach the shared future
        release.countDown();

        Set<McpClient> distinct = ConcurrentHashMap.newKeySet();
        for (Future<McpClient> caller : callers) {
            distinct.add(caller.get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, builds.get(), "one build for one key");
        assertEquals(1, distinct.size(), "every caller gets the same client");
        assertTrue(closed.isEmpty());
    }

    @Test
    @DisplayName("a slow build for one server does not block another server's lookup")
    void slowBuildDoesNotBlockOtherKeys() throws Exception {
        String slowUrl = "http://slow.example/mcp";
        // Chosen to land in the same ConcurrentHashMap bin as the slow key, which is
        // where computeIfAbsent used to block: a return to building inside the map
        // lock fails this test deterministically rather than by chance.
        String fastUrl = sameBinUrl(McpToolProviderManager.cacheKey(config(slowUrl)));
        var slowEntered = new CountDownLatch(1);
        var releaseSlow = new CountDownLatch(1);
        var manager = manager(16, c -> {
            if (c.getUrl().equals(slowUrl)) {
                slowEntered.countDown();
                await(releaseSlow);
            }
            return recordingClient(c.getUrl());
        });

        Future<McpClient> slow = pool.submit(() -> manager.getOrCreateClient(config(slowUrl)));
        assertTrue(slowEntered.await(10, TimeUnit.SECONDS));
        try {
            Future<McpClient> fast = pool.submit(() -> manager.getOrCreateClient(config(fastUrl)));
            fast.get(5, TimeUnit.SECONDS); // throws TimeoutException if it waited on the slow build
        } finally {
            releaseSlow.countDown();
        }
        slow.get(10, TimeUnit.SECONDS);
        assertEquals(2, manager.getActiveConnectionCount());
    }

    @Test
    @DisplayName("a failed build is not cached — the next call builds again")
    void failedBuildReleasesTheKey() {
        var attempts = new AtomicInteger();
        var manager = manager(16, c -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("connection refused");
            }
            return recordingClient(c.getUrl());
        });
        var config = config("http://flaky.example/mcp");

        var failure = assertThrows(IllegalStateException.class, () -> manager.getOrCreateClient(config));
        assertEquals("connection refused", failure.getMessage(), "the build's own exception reaches the caller unwrapped");
        assertEquals(0, manager.getActiveConnectionCount());

        manager.getOrCreateClient(config);
        assertEquals(2, attempts.get());
        assertEquals(1, manager.getActiveConnectionCount());
    }

    @Test
    @DisplayName("shutdown closes every cached client, including one still being built")
    void shutdownClosesAll() throws Exception {
        String inFlightUrl = "http://in-flight.example/mcp";
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var manager = manager(16, c -> {
            if (c.getUrl().equals(inFlightUrl)) {
                entered.countDown();
                await(release);
            }
            return recordingClient(c.getUrl());
        });
        manager.getOrCreateClient(config("http://one.example/mcp"));
        manager.getOrCreateClient(config("http://two.example/mcp"));
        Future<McpClient> inFlight = pool.submit(() -> manager.getOrCreateClient(config(inFlightUrl)));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        manager.shutdown();

        assertEquals(0, manager.getActiveConnectionCount());
        assertTrue(closed.containsKey("http://one.example/mcp") && closed.containsKey("http://two.example/mcp"),
                "built clients are closed synchronously by shutdown: " + closed.keySet());

        release.countDown();
        inFlight.get(10, TimeUnit.SECONDS);
        awaitClosed(3);
        assertTrue(closed.containsKey(inFlightUrl), "a client finished after shutdown dropped its entry is closed, not leaked");
    }

    /**
     * A URL whose cache key falls into the same bin of a default-sized
     * {@link ConcurrentHashMap} as {@code otherKey}.
     */
    private static String sameBinUrl(String otherKey) {
        int bin = bin(otherKey);
        for (int i = 0; i < 10_000; i++) {
            String url = "http://fast" + i + ".example/mcp";
            if (bin(McpToolProviderManager.cacheKey(config(url))) == bin) {
                return url;
            }
        }
        throw new IllegalStateException("no colliding URL found");
    }

    /** ConcurrentHashMap's spread function, masked to its initial 16-bin table. */
    private static int bin(String key) {
        int h = key.hashCode();
        return ((h ^ (h >>> 16)) & 0x7fffffff) & 15;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
