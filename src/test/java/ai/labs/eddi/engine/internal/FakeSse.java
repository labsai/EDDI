/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Plain test doubles for the SSE API — cheap enough to push hundreds of
 * thousands of events through, unlike mocks.
 */
final class FakeSse implements Sse {

    @Override
    public OutboundSseEvent.Builder newEventBuilder() {
        return new Builder();
    }

    @Override
    public SseBroadcaster newBroadcaster() {
        throw new UnsupportedOperationException();
    }

    /** One written event. */
    record Event(String name, String comment, Object data) implements OutboundSseEvent {
        @Override
        public Class<?> getType() {
            return String.class;
        }

        @Override
        public Type getGenericType() {
            return String.class;
        }

        @Override
        public MediaType getMediaType() {
            return MediaType.TEXT_PLAIN_TYPE;
        }

        @Override
        public Object getData() {
            return data;
        }

        @Override
        public String getId() {
            return null;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getComment() {
            return comment;
        }

        @Override
        public long getReconnectDelay() {
            return -1;
        }

        @Override
        public boolean isReconnectDelaySet() {
            return false;
        }
    }

    private static final class Builder implements OutboundSseEvent.Builder {
        private String name;
        private String comment;
        private Object data;

        @Override
        public OutboundSseEvent.Builder id(String id) {
            return this;
        }

        @Override
        public OutboundSseEvent.Builder name(String name) {
            this.name = name;
            return this;
        }

        @Override
        public OutboundSseEvent.Builder reconnectDelay(long milliseconds) {
            return this;
        }

        @Override
        public OutboundSseEvent.Builder mediaType(MediaType mediaType) {
            return this;
        }

        @Override
        public OutboundSseEvent.Builder comment(String comment) {
            this.comment = comment;
            return this;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public OutboundSseEvent.Builder data(Class type, Object data) {
            this.data = data;
            return this;
        }

        @Override
        @SuppressWarnings("rawtypes")
        public OutboundSseEvent.Builder data(GenericType type, Object data) {
            this.data = data;
            return this;
        }

        @Override
        public OutboundSseEvent.Builder data(Object data) {
            this.data = data;
            return this;
        }

        @Override
        public OutboundSseEvent build() {
            return new Event(name, comment, data);
        }
    }

    /**
     * A sink that records what was written. A stalled sink never completes its
     * writes — a client that stopped reading.
     */
    static final class Sink implements SseEventSink {
        final boolean stalled;
        final List<Event> events = new ArrayList<>();
        final List<CompletableFuture<Void>> unfinished = new ArrayList<>();
        volatile boolean closed;

        Sink(boolean stalled) {
            this.stalled = stalled;
        }

        @Override
        public boolean isClosed() {
            return closed;
        }

        @Override
        public synchronized CompletionStage<?> send(OutboundSseEvent event) {
            if (closed) {
                throw new IllegalStateException("Already closed");
            }
            events.add((Event) event);
            if (stalled) {
                var pending = new CompletableFuture<Void>();
                unfinished.add(pending);
                return pending;
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed = true;
        }

        synchronized long count(String name) {
            return events.stream().filter(e -> name.equals(e.name())).count();
        }

        synchronized long comments() {
            return events.stream().filter(e -> e.comment() != null).count();
        }

        synchronized int written() {
            return events.size();
        }
    }
}
