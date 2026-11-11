/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.engine.hitl.oversight.AgentCallMarker;
import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Refuses an oversight write or a HITL decision that an agent's own tool call
 * makes: any non-read request carrying {@link AgentCallMarker#HEADER} to
 * {@code /hitl/samples/**}, {@code /hitl/autonomy/**},
 * {@code /hitl/analytics/rebuild}, a paused conversation's
 * {@code /agents/{id}/resume} (and {@code /resume/stream}), or a group's
 * {@code /groups/{id}/conversations/{id}/approve} (and {@code /approve/stream})
 * and {@code /human-input} answers 403 {@code AGENT_ORIGINATED}. Sample
 * verdicts, every change to earned autonomy and every approval decision are
 * human actions — a resume verdict is counted as human-verified evidence toward
 * earned autonomy.
 * <p>
 * The engine sets the header on an httpcall aimed at this deployment (loopback,
 * {@code eddi.self.base-url}, {@code eddi.hitl.oversight.self-hosts}, or the
 * origin the turn's caller addressed) and on every request that carries a
 * released {@code ${caller:token}}, so not even a forwarded caller token lets
 * an agent produce its own evidence or decide a pause as the human it talks to.
 * A request from outside EDDI — a script, a proxy, another product — carries no
 * such header, and the filter cannot tell it apart from a person.
 *
 * @since 6.7.0
 */
@Provider
public class AgentOriginatedRequestFilter implements ContainerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern GUARDED = Pattern.compile("^/?hitl/(?:samples|autonomy|analytics/rebuild)(?:/.*)?$");
    /** The endpoints that record a human's decision on a HITL pause. */
    private static final Pattern DECISIONS = Pattern
            .compile("^/?(?:agents/[^/]+/resume(?:/stream)?|groups/[^/]+/conversations/[^/]+/(?:approve(?:/stream)?|human-input))/?$");

    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getPath();
        if (refused(request.getMethod(), path, request.getHeaderString(AgentCallMarker.HEADER))) {
            String message = isDecision(path)
                    ? "This request was made by an agent's tool call; deciding a paused conversation is a human action"
                    : "This request was made by an agent's tool call; reviewing samples and changing earned autonomy are human actions";
            request.abortWith(OversightErrors.response(Response.Status.FORBIDDEN, OversightErrors.AGENT_ORIGINATED, message));
        }
    }

    /** Whether a request is refused. */
    static boolean refused(String method, String path, String marker) {
        if (marker == null || method == null || READ_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return false;
        }
        return path != null && (GUARDED.matcher(path.toLowerCase(Locale.ROOT)).matches() || isDecision(path));
    }

    private static boolean isDecision(String path) {
        return path != null && DECISIONS.matcher(path.toLowerCase(Locale.ROOT)).matches();
    }
}
