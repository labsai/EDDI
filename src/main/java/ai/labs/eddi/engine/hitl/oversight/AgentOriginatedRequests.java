/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

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

    /**
     * Whether {@code canonicalPath} (lower case, no matrix parameters, no repeated
     * slashes) is exactly an endpoint that records a human's decision on a HITL
     * pause: {@code agents/{id}/resume} (and {@code /stream}), or
     * {@code groups/{id}/conversations/{id}/approve} (and {@code /stream}) and
     * {@code /human-input}. An identifier may span several segments (an id whose
     * {@code %2F} was decoded).
     * <p>
     * Walks the segments once. A regular expression with two open-ended identifier
     * groups backtracks polynomially on a path made of repeated
     * {@code a/conversations/}, and the path is the caller's to choose.
     */
    public static boolean isDecisionPath(String canonicalPath) {
        if (canonicalPath == null) {
            return false;
        }
        String[] segments = segmentsOf(canonicalPath);
        int n = segments.length;
        if (n >= 3 && "agents".equals(segments[0])) {
            return "resume".equals(segments[n - 1]) || n >= 4 && "stream".equals(segments[n - 1]) && "resume".equals(segments[n - 2]);
        }
        if (n >= 5 && "groups".equals(segments[0])) {
            int decision = decisionIndex(segments);
            for (int i = 2; i <= decision - 2; i++) {
                if ("conversations".equals(segments[i])) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether {@code decodedUri} (decoded, lower case; a whole URI or a path) names
     * such an endpoint anywhere in its path: the unanchored form, for a tool call's
     * target whose prefix is not known. One pass over the segments.
     */
    public static boolean containsDecisionPath(String decodedUri) {
        if (decodedUri == null) {
            return false;
        }
        int end = decodedUri.length();
        for (int i = 0; i < end; i++) {
            char c = decodedUri.charAt(i);
            if (c == '?' || c == '#') {
                // The path ends at the FIRST of them: a query may itself hold a "#"
                // or "?", and kept in the last segment it hid a trailing "resume".
                end = i;
                break;
            }
        }
        String[] segments = segmentsOf(decodedUri.substring(0, end));
        int agents = -1;
        int groups = -1;
        int conversations = -1;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (agents >= 0 && i >= agents + 2 && "resume".equals(segment)) {
                return true;
            }
            if (conversations >= 0 && i >= conversations + 2 && ("approve".equals(segment) || "human-input".equals(segment))) {
                return true;
            }
            if (agents < 0 && "agents".equals(segment)) {
                agents = i;
            }
            if (groups < 0 && "groups".equals(segment)) {
                groups = i;
            } else if (groups >= 0 && conversations < 0 && i >= groups + 2 && "conversations".equals(segment)) {
                conversations = i;
            }
        }
        return false;
    }

    /**
     * The index of the trailing decision segment ({@code approve},
     * {@code human-input}, or {@code approve} before a final {@code stream}), or
     * -1.
     */
    private static int decisionIndex(String[] segments) {
        int last = segments.length - 1;
        if ("approve".equals(segments[last]) || "human-input".equals(segments[last])) {
            return last;
        }
        return "stream".equals(segments[last]) && "approve".equals(segments[last - 1]) ? last - 1 : -1;
    }

    /**
     * The non-empty path segments, each without its matrix parameters
     * ({@code ;x=1}).
     */
    private static String[] segmentsOf(String path) {
        List<String> segments = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= path.length(); i++) {
            if (i == path.length() || path.charAt(i) == '/') {
                String segment = path.substring(start, i);
                int parameters = segment.indexOf(';');
                if (parameters >= 0) {
                    segment = segment.substring(0, parameters);
                }
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
                start = i + 1;
            }
        }
        return segments.toArray(new String[0]);
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
