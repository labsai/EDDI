/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.SecretReference;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProviderResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private final SecretResolver secretResolver = mock(SecretResolver.class);
    /** Opened when a rebuild for a {@link #droppedServer} starts. */
    private final CountDownLatch buildStarted = new CountDownLatch(1);

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private McpToolProviderManager manager(int maxSize, Function<McpServerConfig, McpClient> builder) {
        return manager(maxSize, McpToolProviderManager.DEFAULT_CLIENT_CACHE_EXPIRE_AFTER_ACCESS_MILLIS, builder);
    }

    private McpToolProviderManager manager(int maxSize, long idleMillis, Function<McpServerConfig, McpClient> builder) {
        return new McpToolProviderManager(mock(GlobalVariableResolver.class), secretResolver,
                new CallerIdentityResolver(NO_CALLER, true), NO_CALLER, false, McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS,
                McpToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES, McpToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES_PER_SERVER,
                McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS, maxSize, idleMillis, null) {
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

    /**
     * A recording client that advertises one tool, {@link #toolName}, and answers
     * every call with its server's URL after running {@code onCall}.
     */
    private McpClient toolClient(String url, Runnable onCall) {
        McpClient client = recordingClient(url);
        when(client.listTools()).thenReturn(List.of(ToolSpecification.builder().name(toolName(url)).description("A tool.").build()));
        when(client.executeTool(any(), any())).thenAnswer(invocation -> {
            onCall.run();
            return ToolExecutionResult.builder().resultText("answered by " + url).build();
        });
        return client;
    }

    private static void awaitCondition(BooleanSupplier condition, Runnable poll) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            poll.run();
            Thread.sleep(10);
        }
    }

    private static ToolExecutionRequest request(String url) {
        return ToolExecutionRequest.builder().name(toolName(url)).arguments("{}").build();
    }

    private static String toolName(String url) {
        return "tool_" + URI.create(url).getHost().replace('.', '_');
    }

    private static String call(McpToolProviderManager.McpToolsResult tools, String url) {
        return tools.executors().get(toolName(url)).execute(request(url), null);
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

    @Test
    @DisplayName("a discovered tool whose client was evicted reconnects instead of calling the closed client")
    void evictedClientsToolsReconnect() throws Exception {
        Map<String, List<McpClient>> clients = new ConcurrentHashMap<>();
        var manager = manager(1, c -> {
            McpClient client = toolClient(c.getUrl(), () -> {
            });
            clients.computeIfAbsent(c.getUrl(), url -> new CopyOnWriteArrayList<>()).add(client);
            return client;
        });
        String a = "http://a.example/mcp";
        String b = "http://b.example/mcp";

        var tools = manager.discoverTools(List.of(config(a), config(b)));
        assertEquals(2, tools.executors().size(), "both servers' tools are returned: " + tools.executors().keySet());
        manager.runCacheMaintenance();
        awaitClosed(1);
        assertEquals(1, closed.size(), "one of the two clients is evicted and closed: " + closed.keySet());
        String evicted = closed.keySet().iterator().next();
        McpClient closedClient = clients.get(evicted).get(0);

        assertEquals("answered by " + evicted, call(tools, evicted));
        verify(closedClient, never()).executeTool(any(), any());
        assertTrue(clients.get(evicted).size() >= 2, "the call builds a fresh client for the evicted server");
    }

    @Test
    @DisplayName("a client evicted while a tool call is using it is closed only once the call returns")
    void evictionWaitsForTheCallInFlight() throws Exception {
        String url = "http://busy.example/mcp";
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // Idle eviction after 50 ms: the call below outlasts it.
        var manager = manager(16, 50, c -> toolClient(c.getUrl(), () -> {
            entered.countDown();
            await(release);
        }));
        var tools = manager.discoverTools(List.of(config(url)));

        Future<String> inFlight = pool.submit(() -> call(tools, url));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        // Until the entry has expired and its removal has been handled — either as a
        // deferred close (correct) or as a close (the bug).
        awaitCondition(() -> manager.getActiveConnectionCount() == 0 && (manager.pendingCloseCount() == 1 || !closed.isEmpty()),
                manager::runCacheMaintenance);
        assertEquals(0, manager.getActiveConnectionCount(), "the idle entry is evicted");
        assertTrue(closed.isEmpty(), "the client must stay open under the call that is using it");
        assertEquals(1, manager.pendingCloseCount());

        release.countDown();
        assertEquals("answered by " + url, inFlight.get(10, TimeUnit.SECONDS));
        awaitClosed(1);
        assertTrue(closed.containsKey(url), "the evicted client is closed once its last call finishes");
    }

    /**
     * One server with a discovered tool whose call blocks until {@code release}.
     */
    private McpToolProviderManager busyServer(String url, CountDownLatch entered, CountDownLatch release) {
        return manager(16, c -> toolClient(c.getUrl(), () -> {
            entered.countDown();
            await(release);
        }));
    }

    @Test
    @DisplayName("closeClient during a tool call defers the close until the call returns")
    void closeClientWaitsForTheCallInFlight() throws Exception {
        String url = "http://closing.example/mcp";
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var manager = busyServer(url, entered, release);
        var tools = manager.discoverTools(List.of(config(url)));

        Future<String> inFlight = pool.submit(() -> call(tools, url));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        manager.closeClient(url);
        assertTrue(closed.isEmpty(), "closeClient must not close the client under the call");
        assertEquals(1, manager.pendingCloseCount());

        release.countDown();
        assertEquals("answered by " + url, inFlight.get(10, TimeUnit.SECONDS));
        assertTrue(closed.containsKey(url), "closed as soon as the call released it");
        assertEquals(0, manager.pendingCloseCount());
    }

    @Test
    @DisplayName("a vault secret change during a tool call defers that client's close until the call returns")
    @SuppressWarnings("unchecked")
    void secretRotationWaitsForTheCallInFlight() throws Exception {
        String url = "http://rotating.example/mcp";
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var manager = busyServer(url, entered, release);
        manager.registerSecretInvalidation();
        ArgumentCaptor<Consumer<SecretReference>> listener = ArgumentCaptor.forClass(Consumer.class);
        verify(secretResolver).registerInvalidationListener(listener.capture());
        var tools = manager.discoverTools(List.of(config(url)));

        Future<String> inFlight = pool.submit(() -> call(tools, url));
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        listener.getValue().accept(null);
        assertEquals(0, manager.getActiveConnectionCount(), "rotation drops every cached client");
        assertTrue(closed.isEmpty(), "rotation must not close the client under the call");

        release.countDown();
        inFlight.get(10, TimeUnit.SECONDS);
        assertTrue(closed.containsKey(url), "closed as soon as the call released it");
    }

    @Test
    @DisplayName("a tool call that throws still releases its lease")
    void failedCallReleasesItsLease() throws Exception {
        String url = "http://failing.example/mcp";
        var manager = manager(16, c -> toolClient(c.getUrl(), () -> {
            throw new IllegalStateException("tool blew up");
        }));
        var tools = manager.discoverTools(List.of(config(url)));

        assertThrows(IllegalStateException.class, () -> call(tools, url));
        manager.closeClient(url);
        assertTrue(closed.containsKey(url), "no lease is left behind, so the close is immediate");
        assertEquals(0, manager.pendingCloseCount());
    }

    @Test
    @DisplayName("a client retired between its lookup and its lease is not used; a fresh one is built")
    void clientRetiredBeforeTheLeaseIsNotUsed() throws Exception {
        String url = "http://racing.example/mcp";
        var retireOnNextLookup = new AtomicBoolean();
        List<McpClient> built = new CopyOnWriteArrayList<>();
        var manager = new McpToolProviderManager(mock(GlobalVariableResolver.class), secretResolver,
                new CallerIdentityResolver(NO_CALLER, true), NO_CALLER, false, McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS,
                McpToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES, McpToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES_PER_SERVER,
                McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS, 16,
                McpToolProviderManager.DEFAULT_CLIENT_CACHE_EXPIRE_AFTER_ACCESS_MILLIS, null) {
            @Override
            McpClient buildClient(McpServerConfig config) {
                McpClient client = toolClient(config.getUrl(), () -> {
                });
                built.add(client);
                return client;
            }

            @Override
            McpClient getOrCreateClient(McpServerConfig config) {
                McpClient client = super.getOrCreateClient(config);
                if (retireOnNextLookup.getAndSet(false)) {
                    closeClient(config.getUrl()); // removed and closed after the lookup, before the lease
                }
                return client;
            }
        };
        var tools = manager.discoverTools(List.of(config(url)));
        retireOnNextLookup.set(true);

        assertEquals("answered by " + url, call(tools, url));
        assertEquals(2, built.size(), "the retired client is replaced");
        verify(built.get(0), never()).executeTool(any(), any());
        assertTrue(closed.containsKey(url), "the retired client was closed");
    }

    @Test
    @DisplayName("cancelling an async tool call cancels the call itself and releases the lease")
    void cancellingAnAsyncCallCancelsTheCall() throws Exception {
        String url = "http://async.example/mcp";
        var pending = new CompletableFuture<ToolExecutionResult>();
        var manager = manager(16, c -> {
            McpClient client = toolClient(c.getUrl(), () -> {
            });
            when(client.executeToolAsync(any(), any())).thenReturn(pending);
            return client;
        });
        var tools = manager.discoverTools(List.of(config(url)));

        var call = tools.executors().get(toolName(url)).executeAsync(request(url), null);
        assertTrue(call.cancel(true));
        assertTrue(pending.isCancelled(), "the cancellation must reach the MCP call");
        manager.closeClient(url);
        assertTrue(closed.containsKey(url), "the lease was released");
    }

    @Test
    @DisplayName("rebuilding a client for a tool call goes through the circuit breaker")
    void rebuildForACallUsesTheCircuitBreaker() throws Exception {
        String url = "http://down.example/mcp";
        var serverDown = new AtomicBoolean();
        var manager = manager(16, c -> {
            if (serverDown.get()) {
                throw new IllegalStateException("connection refused");
            }
            return toolClient(c.getUrl(), () -> {
            });
        });
        var tools = manager.discoverTools(List.of(config(url)));
        manager.closeClient(url);
        serverDown.set(true);

        int buildsBefore = builds.get();
        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalStateException.class, () -> call(tools, url));
        }
        assertEquals(buildsBefore + 3, builds.get(), "each failed rebuild is a connect attempt");
        assertTrue(manager.isCircuitOpen(config(url)), "failed rebuilds count towards opening the circuit");

        var refused = assertThrows(IllegalStateException.class, () -> call(tools, url));
        assertTrue(refused.getMessage().contains("circuit breaker"), refused.getMessage());
        assertEquals(buildsBefore + 3, builds.get(), "an open circuit fails the call without connecting");
    }

    /**
     * A server whose tools were discovered and whose client has since been dropped;
     * every rebuild after that throws {@code failure.get()} once {@code buildGate}
     * opens.
     */
    private McpToolProviderManager.McpToolsResult droppedServer(McpToolProviderManager[] holder, String url,
                                                                AtomicReference<RuntimeException> failure, CountDownLatch buildGate) {
        var down = new AtomicBoolean();
        var manager = manager(16, c -> {
            if (down.get()) {
                buildStarted.countDown();
                await(buildGate);
                throw failure.get();
            }
            return toolClient(c.getUrl(), () -> {
            });
        });
        var tools = manager.discoverTools(List.of(config(url)));
        manager.closeClient(url);
        down.set(true);
        holder[0] = manager;
        return tools;
    }

    @Test
    @DisplayName("one failed rebuild shared by concurrent callers counts as one breaker failure")
    void concurrentWaitersOnOneFailedBuildCountOnce() throws Exception {
        String url = "http://blip.example/mcp";
        var holder = new McpToolProviderManager[1];
        var gate = new CountDownLatch(1);
        var tools = droppedServer(holder, url, new AtomicReference<>(new IllegalStateException("connection refused")), gate);
        var manager = holder[0];
        int buildsBefore = builds.get();

        List<Future<String>> calls = new ArrayList<>();
        calls.add(pool.submit(() -> call(tools, url)));
        assertTrue(buildStarted.await(10, TimeUnit.SECONDS));
        // The cache itself may hang a stage on the claim; count waiters from there.
        int baseline = manager.buildWaiterCount(config(url));
        for (int i = 0; i < 2; i++) {
            calls.add(pool.submit(() -> call(tools, url)));
        }
        // The first caller builds; the other two wait on its claim.
        awaitCondition(() -> manager.buildWaiterCount(config(url)) == baseline + 2, () -> {
        });
        assertEquals(baseline + 2, manager.buildWaiterCount(config(url)));
        gate.countDown();
        for (Future<String> call : calls) {
            assertThrows(ExecutionException.class, () -> call.get(10, TimeUnit.SECONDS));
        }

        assertEquals(buildsBefore + 1, builds.get(), "the three callers shared one build");
        assertEquals(1, manager.recentFailureCount(config(url)), "one failed build is one failure");
        assertFalse(manager.isCircuitOpen(config(url)));
    }

    @Test
    @DisplayName("a successful rebuild clears the server's breaker failures, as a successful discovery does")
    void successfulRebuildClearsFailures() throws Exception {
        String url = "http://recovering.example/mcp";
        var serverDown = new AtomicBoolean();
        var manager = manager(16, c -> {
            if (serverDown.get()) {
                throw new IllegalStateException("connection refused");
            }
            return toolClient(c.getUrl(), () -> {
            });
        });
        var tools = manager.discoverTools(List.of(config(url)));
        manager.closeClient(url);
        serverDown.set(true);
        for (int i = 0; i < 2; i++) {
            assertThrows(IllegalStateException.class, () -> call(tools, url));
        }
        assertEquals(2, manager.recentFailureCount(config(url)));

        serverDown.set(false);
        assertEquals("answered by " + url, call(tools, url));
        assertEquals(0, manager.recentFailureCount(config(url)), "the rebuild succeeded, so the failures are cleared");
    }

    @Test
    @DisplayName("a rebuild refused for authentication or a missing credential does not feed the breaker")
    void authAndCredentialFailuresDoNotFeedTheBreaker() throws Exception {
        for (RuntimeException failure : List.of(new IllegalStateException("Unexpected status code: 401"),
                new ConnectionException(ConnectionException.Reason.NOT_CONNECTED, "the user has not connected"))) {
            closed.clear();
            String url = "http://auth" + builds.get() + ".example/mcp";
            var holder = new McpToolProviderManager[1];
            var tools = droppedServer(holder, url, new AtomicReference<>(failure), new CountDownLatch(0));
            for (int i = 0; i < 3; i++) {
                assertThrows(RuntimeException.class, () -> call(tools, url));
            }
            assertEquals(0, holder[0].recentFailureCount(config(url)), "not counted: " + failure);
            assertFalse(holder[0].isCircuitOpen(config(url)));
        }
    }

    @Test
    @DisplayName("a rebuild refused as a configuration error does not feed the breaker")
    void configurationErrorsDoNotFeedTheBreaker() throws Exception {
        String url = "http://misconfigured.example/mcp";
        var holder = new McpToolProviderManager[1];
        var tools = droppedServer(holder, url, new AtomicReference<>(new IllegalArgumentException("URL refused by the SSRF policy")),
                new CountDownLatch(0));
        for (int i = 0; i < 3; i++) {
            assertThrows(IllegalArgumentException.class, () -> call(tools, url));
        }
        assertEquals(0, holder[0].recentFailureCount(config(url)));
        assertFalse(holder[0].isCircuitOpen(config(url)));
    }

    @Test
    @DisplayName("discovery counts a failed client build once, not again in its own catch")
    void discoveryCountsAFailedBuildOnce() {
        String url = "http://unreachable.example/mcp";
        var manager = manager(16, c -> {
            throw new IllegalStateException("connection refused");
        });
        var result = manager.discoverTools(List.of(config(url)));
        assertEquals(McpToolProviderManager.McpFailureKind.CONNECTION_FAILURE, result.failures().get(0).kind());
        assertEquals(1, manager.recentFailureCount(config(url)));
    }

    @Test
    @DisplayName("discovery does not count a client retired under it as a breaker failure")
    void discoveryDoesNotCountARetiredClient() {
        String url = "http://churning.example/mcp";
        var manager = new McpToolProviderManager(mock(GlobalVariableResolver.class), secretResolver,
                new CallerIdentityResolver(NO_CALLER, true), NO_CALLER, false, McpToolProviderManager.DEFAULT_MAX_DESCRIPTION_CHARS,
                McpToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES, McpToolProviderManager.DEFAULT_MAX_SCHEMA_BYTES_PER_SERVER,
                McpToolProviderManager.DEFAULT_TOOL_CACHE_TTL_MILLIS, 16,
                McpToolProviderManager.DEFAULT_CLIENT_CACHE_EXPIRE_AFTER_ACCESS_MILLIS, null) {
            @Override
            ToolProviderResult fetchToolsFromServer(McpServerConfig serverConfig) {
                throw new McpToolProviderManager.ClientRetiredException("evicted again as soon as it was built");
            }
        };
        var result = manager.discoverTools(List.of(config(url)));
        assertEquals(1, result.failures().size(), "the server is still reported as contributing nothing");
        assertEquals(0, manager.recentFailureCount(config(url)), "the cache's churn says nothing about the server");
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
