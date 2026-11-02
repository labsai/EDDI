/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.httpclient.IRequest;

/**
 * The engine-owned header every outbound httpcall carries, so this server can
 * refuse an oversight write an agent's own tool call makes — even one that
 * forwards the caller's token ({@code ${caller:token}}).
 * <p>
 * Set last, after the configured headers, so a configuration cannot remove or
 * replace it. Its value is a constant: no agent or conversation identifier
 * leaves the deployment with it.
 *
 * @since 6.7.0
 */
public final class AgentCallMarker {

    /** The header name. */
    public static final String HEADER = "X-EDDI-Agent-Call";
    /** The header value. */
    public static final String VALUE = "true";

    private AgentCallMarker() {
    }

    /** Marks a request as made by an agent's tool call. */
    public static void apply(IRequest request) {
        if (request != null) {
            request.setHttpHeader(HEADER, VALUE);
        }
    }
}
