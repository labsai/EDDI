/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.modules.llm.tools.ToolExecutionService;

import java.util.List;

/**
 * Lets a test in any package stand in for an inbound REST or MCP request that
 * carries {@link TurnOrigin#HEADER}: until the returned handle is closed,
 * {@link TurnOrigin#fromRequest()} answers with the header's values, exactly as
 * it would for a live request — and, like a live request, only on the thread
 * that serves it. Work handed to another thread sees no request, so a test
 * through this seam catches a thread hop that forgets to carry the taint.
 */
public final class TurnOriginTestSupport {

    private TurnOriginTestSupport() {
    }

    /**
     * An inbound request whose {@code X-Eddi-Turn-Origin} header holds
     * {@code headerValues} (none: a request without the header).
     */
    public static AutoCloseable inboundRequest(String... headerValues) {
        String origin = TurnOrigin.fromHeaderValues(headerValues == null ? null : List.of(headerValues));
        Thread requestThread = Thread.currentThread();
        return TurnOrigin.useRequestSource(() -> Thread.currentThread() == requestThread ? origin : null);
    }

    /**
     * A real {@link ToolExecutionService}, the boundary that refuses a guarded tool
     * in a tainted turn.
     */
    public static ToolExecutionService toolExecutionService() throws Exception {
        return ConversationServiceTurnOriginTest.toolExecutionService();
    }
}
