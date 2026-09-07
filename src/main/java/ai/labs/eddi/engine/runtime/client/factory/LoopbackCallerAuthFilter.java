/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import org.jboss.logging.Logger;

/**
 * Carries the calling user's bearer token across EDDI's internal loopback HTTP
 * hop, so a request that arrived authenticated stays authenticated when a
 * service re-enters the API through {@link RestInterfaceFactory}.
 *
 * <h3>The bug this fixes</h3> Several services call EDDI's own REST API rather
 * than the stores — {@code AgentSetupService} (behind the agent wizard, the
 * {@code setup-api} endpoint and therefore the Platform Operator's
 * agent-creation tools) and most of {@code McpAdminTools}. The client the
 * factory built carried no credentials, while {@code /*} is behind the
 * {@code authenticated} HTTP policy. So every one of those paths answered
 * <b>401 whenever {@code authorization.enabled=true}</b> — which is exactly the
 * configuration per-user workspaces require. The wizard, setup-api and the
 * operator's write capability were unusable on any Keycloak-protected
 * deployment, and the failure looked like a server fault rather than a missing
 * credential.
 *
 * <h3>Why forwarding the token here is safe</h3> The one-argument
 * {@link RestInterfaceFactory#get(Class)} addresses {@code 127.0.0.1} on this
 * process's own HTTP port. The destination is not merely same-origin, it is
 * this very process — so the token is handed back to the service that issued
 * the request it came from. The two-argument overload, which names an arbitrary
 * remote instance for cross-instance sync, deliberately does <b>not</b> get
 * this filter: that is the case where forwarding a token would leak it.
 *
 * <h3>Consequences worth knowing</h3> With the caller's token attached, the
 * inner request authenticates as the real user rather than as nobody. So
 * {@code DocumentDescriptorFilter} stamps resources created through setup-api
 * with their actual owner instead of leaving them unowned, and the
 * {@code IRest*Store} facades apply {@code ResourceAccessGuard} against the
 * person who asked — which is what makes the Platform Operator behave correctly
 * under workspaces rather than either failing or over-reaching.
 *
 * @author ginccc
 */
@ApplicationScoped
public class LoopbackCallerAuthFilter implements ClientRequestFilter {

    private static final Logger LOGGER = Logger.getLogger(LoopbackCallerAuthFilter.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private final CallerIdentityContext callerIdentityContext;

    @Inject
    public LoopbackCallerAuthFilter(CallerIdentityContext callerIdentityContext) {
        this.callerIdentityContext = callerIdentityContext;
    }

    @Override
    public void filter(ClientRequestContext requestContext) {
        if (requestContext.getHeaders().containsKey(HttpHeaders.AUTHORIZATION)) {
            // An explicit header wins. Nothing sets one today, but a caller that does
            // has said what it wants and should not be silently overridden.
            return;
        }

        String token = currentToken();
        if (token != null) {
            requestContext.getHeaders().putSingle(HttpHeaders.AUTHORIZATION, BEARER_PREFIX + token);
        }
    }

    /**
     * The calling user's raw bearer token, or {@code null} when there is none.
     * <p>
     * Two sources, in order. A pipeline worker thread has an identity
     * <em>bound</em> by {@link CallerIdentityContext} — the request thread is long
     * gone there, so {@code capture()} would find nothing. A request thread has no
     * binding but a live security context, which {@code capture()} reads. Checking
     * the binding first also keeps a HITL resume attributed to the user whose turn
     * it is rather than to the administrator who approved it.
     * <p>
     * {@code null} is an ordinary answer, not a failure: with OIDC disabled there
     * is no token, and the inner call is permitted anyway.
     */
    private String currentToken() {
        try {
            CallerIdentity bound = callerIdentityContext.current();
            if (bound != null && bound.hasToken()) {
                return bound.token();
            }
            CallerIdentity captured = callerIdentityContext.capture();
            return captured != null && captured.hasToken() ? captured.token() : null;
        } catch (RuntimeException e) {
            // Never fail the inner call over this: without a token it proceeds exactly as
            // it did before this filter existed.
            LOGGER.debugf("Could not resolve a caller token for the loopback call: %s", e.getMessage());
            return null;
        }
    }
}
