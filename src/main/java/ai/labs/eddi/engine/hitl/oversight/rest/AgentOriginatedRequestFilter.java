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
 * Refuses an oversight write that an agent's own tool call makes: any non-read
 * request carrying {@link AgentCallMarker#HEADER} to {@code /hitl/samples/**},
 * {@code /hitl/autonomy/**}, {@code /hitl/analytics/rebuild} or
 * {@code /administration/spend-control-changes/**} answers 403
 * {@code AGENT_ORIGINATED}. Sample verdicts, every change to earned autonomy
 * and approving a looser price or quota are human actions; the engine sets the
 * header on every httpcall, so not even a forwarded caller token lets an agent
 * produce its own evidence or approve its own budget.
 *
 * @since 6.7.0
 */
@Provider
public class AgentOriginatedRequestFilter implements ContainerRequestFilter {

    private static final Set<String> READ_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern GUARDED = Pattern
            .compile("^/?(?:hitl/(?:samples|autonomy|analytics/rebuild)|administration/spend-control-changes)(?:/.*)?$");

    @Override
    public void filter(ContainerRequestContext request) {
        if (refused(request.getMethod(), request.getUriInfo().getPath(), request.getHeaderString(AgentCallMarker.HEADER))) {
            request.abortWith(OversightErrors.response(Response.Status.FORBIDDEN, OversightErrors.AGENT_ORIGINATED,
                    "This request was made by an agent's tool call; reviewing samples, changing earned autonomy and deciding "
                            + "spend-control changes are human actions"));
        }
    }

    /** Whether a request is refused. */
    static boolean refused(String method, String path, String marker) {
        if (marker == null || method == null || READ_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return false;
        }
        return path != null && GUARDED.matcher(path.toLowerCase(Locale.ROOT)).matches();
    }
}
