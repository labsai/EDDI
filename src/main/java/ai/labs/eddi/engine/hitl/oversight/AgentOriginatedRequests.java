/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The one answer to "did an agent's own tool call make this request?", shared
 * by the REST filter ({@code AgentOriginatedRequestFilter}) and the MCP tools
 * that decide a HITL pause.
 * <p>
 * A request carries {@link AgentCallMarker#HEADER} when the engine sent it on
 * an agent's behalf — an httpcall or an MCP client call aimed at this
 * deployment, or any request carrying a released {@code ${caller:token}}. The
 * REST filter reads the header from the JAX-RS request. An MCP tool runs inside
 * the MCP server, which the filter never sees, and calls the services
 * in-process; it reads the header here instead: quarkus-mcp-server's
 * {@code StreamableHttpMcpMessageHandler} sets {@link CurrentVertxRequest} to
 * the inbound {@code /mcp} request whenever it activates the request context
 * for a message, so the header of the very HTTP request that carried the tool
 * call is available to the tool.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class AgentOriginatedRequests {

    private static final Logger LOGGER = Logger.getLogger(AgentOriginatedRequests.class);

    /** The refusal text for a HITL decision, REST and MCP alike. */
    public static final String DECISION_REFUSAL = "This request was made by an agent's tool call; deciding a paused conversation is a "
            + "human action";

    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public AgentOriginatedRequests(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    /** Whether a request whose marker header has this value is an agent's. */
    public static boolean isMarked(String markerHeaderValue) {
        return markerHeaderValue != null;
    }

    /**
     * Whether the HTTP request being served carries the marker. False when there is
     * no HTTP request (a scheduled job, an in-process call) — such a call was not
     * sent by an agent's tool over HTTP.
     */
    public boolean currentRequestIsMarked() {
        try {
            var current = currentVertxRequest.getCurrent();
            return current != null && isMarked(current.request().getHeader(AgentCallMarker.HEADER));
        } catch (RuntimeException e) {
            LOGGER.debugf("No active request to read %s from: %s", AgentCallMarker.HEADER, e.getMessage());
            return false;
        }
    }

    /** {@link #currentRequestIsMarked()} through a reader that may be absent. */
    public static boolean currentRequestIsMarked(AgentOriginatedRequests reader) {
        return reader != null && reader.currentRequestIsMarked();
    }
}
