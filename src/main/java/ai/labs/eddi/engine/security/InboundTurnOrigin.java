/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.RequestScoped;

/**
 * The origin taint an inbound REST or MCP request carries, bound explicitly for
 * the request it arrived with (email plan v3 §5.3):
 * {@link TaintedRequestFilter} binds it for every Quarkus REST request,
 * {@code McpErrorResultInterceptor} for every call of one of EDDI's MCP tools.
 * {@link TurnOrigin#current()} reads it, so whatever the request starts or
 * continues on its own thread — a conversation, a schedule, a group discussion,
 * a backlog task — is tainted.
 * <p>
 * Request-scoped and sticky: a binding can add the taint, never remove it.
 * {@link #approved()} is what {@link TurnOrigin#APPROVED_HEADER} said, which
 * only counts on a tainted request.
 */
@RequestScoped
@Unremovable
public class InboundTurnOrigin {

    private volatile String origin;
    private volatile boolean approved;

    /** Adds {@code taint}, and the sender's statement that a person approved it. */
    public void bind(String taint, boolean approvedCall) {
        origin = TurnOrigin.combine(origin, taint);
        approved = approved || approvedCall;
    }

    /** The bound taint, or {@code null}. */
    public String origin() {
        return origin;
    }

    /**
     * Whether the sender of this tainted request stated that a person approved the
     * call ({@link TurnOrigin#APPROVED_HEADER}); {@code false} on an untainted
     * request.
     */
    public boolean approved() {
        return origin != null && approved;
    }
}
