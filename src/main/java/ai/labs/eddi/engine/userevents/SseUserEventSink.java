/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** {@link UserEventSink} over a JAX-RS SSE response. */
final class SseUserEventSink implements UserEventSink {

    private final SseEventSink sink;
    private final Sse sse;

    SseUserEventSink(SseEventSink sink, Sse sse) {
        this.sink = sink;
        this.sse = sse;
    }

    @Override
    public CompletionStage<?> send(String name, String data) {
        try {
            return sink.send(sse.newEventBuilder().name(name).data(String.class, data).build());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<?> comment(String text) {
        try {
            return sink.send(sse.newEventBuilder().comment(text).build());
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public boolean isClosed() {
        return sink.isClosed();
    }

    @Override
    public void close() {
        try {
            if (!sink.isClosed()) {
                sink.close();
            }
        } catch (RuntimeException ignored) {
            // the client went away first
        }
    }
}
