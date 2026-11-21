/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Map;

/**
 * The receiving side of the escalation guard (email plan v3 §5.3): a request
 * that carries the origin taint in {@link TurnOrigin#HEADER} and writes to one
 * of {@link EscalationGuard#GUARDED_PATHS} answers {@code 403}.
 * <p>
 * A tainted turn's own outbound request to such a path is already refused
 * before it is sent ({@code ApiCallExecutor}, {@code OutboundHttp}). This check
 * is for the requests that reach EDDI anyway — through a host, a proxy or a
 * client the sending side did not recognise — so the decision is made where the
 * endpoint is, too. A request without the header is untouched: the taint can
 * only be added by a header, never removed.
 */
@Provider
public class TaintedRequestFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext request) {
        List<String> origin = request.getHeaders() == null ? null : request.getHeaders().get(TurnOrigin.HEADER);
        if (refused(request.getMethod(), request.getUriInfo().getPath(), origin)) {
            request.abortWith(Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(Map.of("error", EscalationGuard.REFUSAL, "errorCode", "EMAIL_ORIGIN")).build());
        }
    }

    /** Whether a request with this method, path and taint header is refused. */
    static boolean refused(String method, String path, List<String> originHeaderValues) {
        return TurnOrigin.fromHeaderValues(originHeaderValues) != null && EscalationGuard.isGuardedRequest(method, path);
    }
}
