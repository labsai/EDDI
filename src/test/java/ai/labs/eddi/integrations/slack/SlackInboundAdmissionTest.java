/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.CacheImpl;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.integrations.slack.SlackEventHandler.Admission;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Admission of inbound Slack events: bounded concurrency with a bounded queue,
 * a retryable refusal past it, and de-duplication that neither throws on nor
 * collapses events without an {@code event_id}.
 * <p>
 * The event map itself is the probe: {@code handleEvent} reads
 * {@code event.get("type")} first, so a map that blocks there holds its
 * processing slot for as long as the test wants, and counts how many events are
 * inside at once. It answers {@code bot_id} afterwards, so each event then
 * returns without touching any collaborator.
 */
@DisplayName("SlackEventHandler — inbound admission")
class SlackInboundAdmissionTest {

    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger inside = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final AtomicInteger processed = new AtomicInteger();
    private SlackEventHandler handler;
    private final ChannelTargetRouter router = mock(ChannelTargetRouter.class);
    private final ObserveGate observeGate = mock(ObserveGate.class);
    private final IUserConversationStore userConversationStore = mock(IUserConversationStore.class);

    @AfterEach
    void tearDown() {
        release.countDown();
        if (handler != null) {
            handler.shutdown();
        }
    }

    /**
     * The production node-local cache (Caffeine behind {@link CacheImpl}), so a
     * null key behaves exactly as it does in a deployment.
     */
    private SlackEventHandler handler(int maxConcurrent, int maxQueued) {
        var cacheFactory = mock(ICacheFactory.class);
        doAnswer(invocation -> new CacheImpl<>(invocation.getArgument(0), Caffeine.newBuilder().build()))
                .when(cacheFactory).getCache(anyString(), any(Duration.class));
        handler = new SlackEventHandler(router, observeGate, mock(ToolCostTracker.class),
                mock(SlackWebApiClient.class), mock(IConversationService.class), mock(IGroupConversationService.class),
                userConversationStore, cacheFactory,
                new SlackConfig(SlackConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS, SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS,
                        SlackConfig.DEFAULT_API_MAX_RETRIES, SlackConfig.DEFAULT_API_RETRY_BASE_MS, maxConcurrent, maxQueued),
                new InMemorySlackApprovalRecordStore(), mock(IUserMemoryStore.class));
        return handler;
    }

    /** An event whose first read blocks until {@link #release} opens. */
    private HashMap<String, Object> blockingEvent() {
        return new HashMap<>() {
            @Override
            public Object get(Object key) {
                if ("type".equals(key)) {
                    int now = inside.incrementAndGet();
                    peak.accumulateAndGet(now, Math::max);
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        inside.decrementAndGet();
                        processed.incrementAndGet();
                    }
                    return "message";
                }
                return super.get(key);
            }

            @Override
            public boolean containsKey(Object key) {
                return "bot_id".equals(key) || super.containsKey(key);
            }
        };
    }

    private static void awaitValue(AtomicInteger value, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (value.get() < expected && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @Test
    @DisplayName("a burst past running + queued is refused as OVERLOADED, and concurrency never exceeds the bound")
    void burstIsBounded() throws Exception {
        var handler = handler(2, 1);

        List<Admission> admissions = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            admissions.add(handler.handleEventAsync("evt-" + i, blockingEvent(), null));
        }

        assertEquals(List.of(Admission.ACCEPTED, Admission.ACCEPTED, Admission.ACCEPTED, Admission.OVERLOADED, Admission.OVERLOADED,
                Admission.OVERLOADED), admissions, "2 running + 1 queued are admitted; the rest are refused at once");
        awaitValue(inside, 2);
        Thread.sleep(200); // give the queued event every chance to (wrongly) start
        assertEquals(2, inside.get(), "only maxConcurrent events run at once");

        release.countDown();
        awaitValue(processed, 3);
        assertEquals(3, processed.get(), "every admitted event — the queued one too — is processed");
        assertEquals(2, peak.get());
    }

    @Test
    @DisplayName("a refused event's id is released, so Slack's redelivery is processed, not skipped as a duplicate")
    void overloadReleasesTheDedupClaim() throws Exception {
        var handler = handler(1, 0);
        assertEquals(Admission.ACCEPTED, handler.handleEventAsync("evt-busy", blockingEvent(), null));
        assertEquals(Admission.OVERLOADED, handler.handleEventAsync("evt-retry", blockingEvent(), null));

        release.countDown();
        awaitValue(processed, 1);
        // The slot is released just after processing; Slack's redelivery comes later.
        Admission redelivery = Admission.OVERLOADED;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (redelivery == Admission.OVERLOADED && System.nanoTime() < deadline) {
            redelivery = handler.handleEventAsync("evt-retry", blockingEvent(), null);
            if (redelivery == Admission.OVERLOADED) {
                Thread.sleep(10);
            }
        }
        assertEquals(Admission.ACCEPTED, redelivery);
        awaitValue(processed, 2);
        assertEquals(2, processed.get());
    }

    @Test
    @DisplayName("a duplicate event_id is still de-duplicated")
    void duplicateIsSkipped() throws Exception {
        var handler = handler(4, 4);
        release.countDown();
        assertEquals(Admission.ACCEPTED, handler.handleEventAsync("evt-same", blockingEvent(), null));
        assertEquals(Admission.DUPLICATE, handler.handleEventAsync("evt-same", blockingEvent(), null));
        awaitValue(processed, 1);
        Thread.sleep(100);
        assertEquals(1, processed.get());
    }

    @Test
    @DisplayName("events without an event_id are processed — no exception, and distinct events are not collapsed")
    void missingEventIdIsProcessed() throws Exception {
        var handler = handler(4, 4);
        release.countDown();
        assertEquals(Admission.ACCEPTED, handler.handleEventAsync(null, blockingEvent(), null));
        assertEquals(Admission.ACCEPTED, handler.handleEventAsync(null, blockingEvent(), null));
        assertEquals(Admission.ACCEPTED, handler.handleEventAsync("  ", blockingEvent(), null));
        awaitValue(processed, 3);
        assertEquals(3, processed.get(), "each id-less event is handled on its own");
    }

    @Test
    @DisplayName("an observer's turn keeps its processing slot until it finishes")
    void observerTurnHoldsItsSlot() throws Exception {
        var target = mock(ChannelTarget.class);
        when(target.getTargetId()).thenReturn("agent-1");
        when(target.getName()).thenReturn("observer");
        when(router.observeCandidates("slack", "C1")).thenReturn(List.of(target));
        when(router.getBotToken("slack", "C1")).thenReturn("test-bot-token");
        when(observeGate.select(eq("slack"), eq("C1"), anyList(), any(), anyList()))
                .thenReturn(Optional.of(new ObserveGate.Match(target, null)));
        // The observer turn's first collaborator: block there, as a slow turn would.
        doAnswer(invocation -> {
            int now = inside.incrementAndGet();
            peak.accumulateAndGet(now, Math::max);
            try {
                release.await(30, TimeUnit.SECONDS);
            } finally {
                inside.decrementAndGet();
                processed.incrementAndGet();
            }
            return null;
        }).when(userConversationStore).readUserConversation(anyString(), anyString());

        var handler = handler(1, 0);
        assertEquals(Admission.ACCEPTED, handler.handleEventAsync("evt-observed-1", observableMessage("1.0"), null));
        awaitValue(inside, 1);
        Thread.sleep(200); // the admitted handler would have returned by now if the turn ran elsewhere

        assertEquals(Admission.OVERLOADED, handler.handleEventAsync("evt-observed-2", observableMessage("2.0"), null),
                "the only slot is still taken by the first observer turn");
        release.countDown();
        awaitValue(processed, 1);
        assertEquals(1, peak.get(), "observer turns never run beyond max-concurrent-events");
    }

    private static HashMap<String, Object> observableMessage(String ts) {
        var event = new HashMap<String, Object>();
        event.put("type", "message");
        event.put("channel", "C1");
        event.put("user", "U12345");
        event.put("text", "has anyone seen the build fail?");
        event.put("ts", ts);
        return event;
    }

    @Test
    @DisplayName("an accepted event still waiting for a slot at shutdown is dropped with a warning, not silently")
    void shutdownLogsAWaitingEventItDrops() throws Exception {
        var handler = handler(1, 1);
        assertEquals(Admission.ACCEPTED, handler.handleEventAsync("evt-running", blockingEvent(), null));
        awaitValue(inside, 1);
        assertEquals(Admission.ACCEPTED, handler.handleEventAsync("evt-waiting", blockingEvent(), null));

        List<String> captured = new CopyOnWriteArrayList<>();
        Handler logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                captured.add(String.valueOf(record.getMessage()));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        // logging.properties turns the ai.labs.eddi namespace OFF for plain unit tests.
        Logger julLogger = Logger.getLogger(SlackEventHandler.class.getName());
        Level previousLevel = julLogger.getLevel();
        julLogger.setLevel(Level.ALL);
        julLogger.addHandler(logHandler);
        try {
            handler.shutdown(Duration.ofMillis(200));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (captured.stream().noneMatch(m -> m.contains("evt-waiting")) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
        } finally {
            julLogger.removeHandler(logHandler);
            julLogger.setLevel(previousLevel);
        }

        assertTrue(captured.stream().anyMatch(m -> m.contains("dropping Slack event evt-waiting")),
                "the dropped event must be named in a warning; captured: " + captured);
    }

    @Test
    @DisplayName("the shipped defaults admit 32 running and 256 queued")
    void defaults() {
        var config = new SlackConfig(60, 300, 3, 500);
        assertEquals(32, config.getMaxConcurrentEvents());
        assertEquals(256, config.getMaxQueuedEvents());
        var invalid = new SlackConfig(60, 300, 3, 500, 0, -1);
        assertEquals(SlackConfig.DEFAULT_MAX_CONCURRENT_EVENTS, invalid.getMaxConcurrentEvents());
        assertEquals(SlackConfig.DEFAULT_MAX_QUEUED_EVENTS, invalid.getMaxQueuedEvents());
    }
}
