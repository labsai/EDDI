/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The receiving side of the escalation guard (email plan v3 §5.3) for REST.
 * <ol>
 * <li>It <b>binds</b> the origin taint a request carries in
 * {@link TurnOrigin#HEADER} on the request ({@link InboundTurnOrigin}), so
 * whatever the request starts or continues — a conversation through {@code POST
 * /agents/{id}/start}, a schedule, a group discussion, a backlog task — is
 * tainted ({@link TurnOrigin#current()}). A request whose taint cannot be bound
 * is refused rather than served untainted.</li>
 * <li>It <b>refuses</b> a tainted request that writes to one of
 * {@link EscalationGuard#GUARDED_PATHS} with {@code 403}, and one that writes
 * configuration ({@link EscalationGuard#CONFIG_PATHS}) unless the sender stated
 * that a person approved the call ({@link TurnOrigin#APPROVED_HEADER}).</li>
 * </ol>
 * A tainted turn's own outbound request to such a path is already refused
 * before it is sent ({@code ApiCallExecutor}, {@code OutboundHttp}). This check
 * is for the requests that reach EDDI anyway — through a host, a proxy or a
 * client the sending side did not recognise — so the decision is made where the
 * endpoint is, too. A request without the header is untouched: the taint can
 * only be added by a header, never removed.
 * <p>
 * Runs after {@code LegacyPathRewriteFilter} (pre-matching), so it sees the
 * current store names.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class TaintedRequestFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext request) {
        List<String> origin = header(request, TurnOrigin.HEADER);
        List<String> approved = header(request, TurnOrigin.APPROVED_HEADER);
        boolean tainted;
        try {
            tainted = TurnOrigin.bindInbound(origin, approved) != null;
        } catch (RuntimeException e) {
            request.abortWith(forbidden(EscalationGuard.REFUSAL));
            return;
        }
        refusal(request.getMethod(), request.getUriInfo().getPath(), tainted, TurnOrigin.approvedFromHeaderValues(approved))
                .ifPresent(reason -> request.abortWith(forbidden(reason)));
    }

    /** The refusal for a request with this method, path, taint and approval. */
    static Optional<String> refusal(String method, String path, boolean tainted, boolean approved) {
        return EscalationGuard.refuseInbound(method, path, tainted, approved);
    }

    /** Whether a request with this method, path and taint header is refused. */
    static boolean refused(String method, String path, List<String> originHeaderValues) {
        return refusal(method, path, TurnOrigin.fromHeaderValues(originHeaderValues) != null, false).isPresent();
    }

    private static List<String> header(ContainerRequestContext request, String name) {
        return request.getHeaders() == null ? null : request.getHeaders().get(name);
    }

    private static Response forbidden(String reason) {
        return Response.status(Response.Status.FORBIDDEN).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(Map.of("error", reason, "errorCode", "EMAIL_ORIGIN")).build();
    }
}
