/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.impl;

import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.nlp.IInputParser;
import jakarta.inject.Provider;
import jakarta.ws.rs.container.AsyncResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Concurrency and bounding tests for the parser cache of
 * {@link RestSemanticParser}.
 * <p>
 * {@code RestSemanticParser} is an {@code @ApplicationScoped} singleton whose
 * parsers are created on {@code IRuntime} pool threads, so its cache is mutated
 * concurrently. Before the fix the cache was an unsynchronised {@link HashMap}
 * with a {@code containsKey}/{@code put} sequence, unbounded, and keyed on a
 * caller-supplied config id.
 */
@DisplayName("RestSemanticParser — parser cache")
@SuppressWarnings("unchecked")
class RestSemanticParserCacheTest {

    private static final String CONFIG_ID = "aabbccdd11223344eeff5566";

    private IRuntime runtime;
    private IResourceClientLibrary resourceClientLibrary;
    private Provider<ILifecycleTask> parserProvider;
    private ILifecycleTask parserTask;
    private IInputParser inputParser;
    private AsyncResponse asyncResponse;
    private RestSemanticParser parser;

    @BeforeEach
    void setUp() throws Exception {
        runtime = mock(IRuntime.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        parserProvider = mock(Provider.class);
        parserTask = mock(ILifecycleTask.class);
        inputParser = mock(IInputParser.class);
        asyncResponse = mock(AsyncResponse.class);

        Map<String, Provider<ILifecycleTask>> lifecycleTasks = new HashMap<>();
        lifecycleTasks.put("ai.labs.parser", parserProvider);
        parser = new RestSemanticParser(runtime, resourceClientLibrary, lifecycleTasks);

        doReturn(parserTask).when(parserProvider).get();
        doReturn(inputParser).when(parserTask).configure(any(), any());
        doReturn(List.of()).when(inputParser).parse(anyString());
        doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());
    }

    private List<Callable<Void>> captureCallables(int expectedInvocations) {
        ArgumentCaptor<Callable<Void>> captor = ArgumentCaptor.forClass(Callable.class);
        verify(runtime, times(expectedInvocations)).submitCallable(captor.capture(), isNull());
        return captor.getAllValues();
    }

    @Test
    @Timeout(60)
    @DisplayName("concurrent requests for the same uncached parser create exactly one parser instance")
    void concurrentRequestsCreateExactlyOneParser() throws Exception {
        AtomicInteger configurationLoads = new AtomicInteger();
        // Two parties: both threads only ever reach the loader if the cache lets
        // them in concurrently, which is precisely the bug.
        CountDownLatch insideLoader = new CountDownLatch(2);

        doAnswer(invocation -> {
            configurationLoads.incrementAndGet();
            insideLoader.countDown();
            // Holds the creation open so a second thread has ample opportunity to
            // slip into the old containsKey/put window. With a single-flight cache
            // only one thread arrives here and this await simply times out.
            insideLoader.await(1, TimeUnit.SECONDS);
            return new ParserConfiguration();
        }).when(resourceClientLibrary).getResource(any(), eq(ParserConfiguration.class));

        AsyncResponse secondResponse = mock(AsyncResponse.class);
        parser.parse(CONFIG_ID, 1, "first", asyncResponse);
        parser.parse(CONFIG_ID, 1, "second", secondResponse);

        List<Callable<Void>> callables = captureCallables(2);
        assertEquals(2, callables.size());

        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Void>> futures = new ArrayList<>();
            for (Callable<Void> callable : callables) {
                futures.add(pool.submit(() -> {
                    startTogether.await(20, TimeUnit.SECONDS);
                    return callable.call();
                }));
            }
            for (Future<Void> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, configurationLoads.get(),
                "parser configuration must be fetched exactly once for concurrent requests");
        verify(parserProvider, times(1)).get();
        verify(parserTask, times(1)).configure(any(), any());

        // Both callers still get a successful result, served by the single instance.
        assertResumedWithSolutionList(asyncResponse);
        assertResumedWithSolutionList(secondResponse);
        verify(inputParser).parse("first");
        verify(inputParser).parse("second");
    }

    private void assertResumedWithSolutionList(AsyncResponse response) {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(response).resume(captor.capture());
        assertInstanceOf(List.class, captor.getValue());
    }

    @Test
    @Timeout(60)
    @DisplayName("cache stays bounded when callers request more parser ids than the maximum")
    void cacheIsBoundedByMaximumSize() throws Exception {
        doReturn(new ParserConfiguration()).when(resourceClientLibrary).getResource(any(), eq(ParserConfiguration.class));

        int maxCachedParsers = RestSemanticParser.MAX_CACHED_PARSERS;
        int distinctConfigIds = maxCachedParsers + 50;
        for (int i = 0; i < distinctConfigIds; i++) {
            parser.parse(String.format("%024x", i), 1, "hello", asyncResponse);
        }
        for (Callable<Void> callable : captureCallables(distinctConfigIds)) {
            callable.call();
        }

        // Every distinct id really was resolved — i.e. we genuinely tried to cache
        // more entries than the maximum.
        verify(parserProvider, times(distinctConfigIds)).get();

        long cached = parser.cachedParserCount();
        assertTrue(cached > 0, "cache should retain entries, but was empty");
        assertTrue(cached <= maxCachedParsers,
                "cache must be bounded by " + maxCachedParsers + " entries, but held " + cached);
    }

    @Test
    @Timeout(60)
    @DisplayName("invalidateCache drops cached parsers so an updated configuration is re-read")
    void invalidateCacheForcesReload() throws Exception {
        doReturn(new ParserConfiguration()).when(resourceClientLibrary).getResource(any(), eq(ParserConfiguration.class));

        parser.parse(CONFIG_ID, 1, "first", asyncResponse);
        captureCallables(1).getFirst().call();
        assertEquals(1, parser.cachedParserCount());

        parser.invalidateCache();
        assertEquals(0, parser.cachedParserCount(), "invalidateCache must empty the cache");

        reset(runtime);
        doReturn(mock(Future.class)).when(runtime).submitCallable(any(Callable.class), any());
        parser.parse(CONFIG_ID, 1, "second", mock(AsyncResponse.class));
        captureCallables(1).getFirst().call();

        verify(parserProvider, times(2)).get();
        verify(resourceClientLibrary, times(2)).getResource(any(), eq(ParserConfiguration.class));
    }
}
