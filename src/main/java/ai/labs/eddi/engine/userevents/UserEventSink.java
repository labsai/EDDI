/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import java.util.concurrent.CompletionStage;

/**
 * Where one connection's frames go: the SSE response of one browser tab. An
 * interface so the hub can be tested without an HTTP server.
 */
public interface UserEventSink {

    /**
     * Writes one named event. The stage completes when the frame was written, and
     * completes exceptionally when the client is gone.
     */
    CompletionStage<?> send(String name, String data);

    /** Writes an SSE comment line — the keep-alive. */
    CompletionStage<?> comment(String text);

    boolean isClosed();

    void close();
}
