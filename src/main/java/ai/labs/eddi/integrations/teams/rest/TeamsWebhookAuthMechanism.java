/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams.rest;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AnonymousAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Keeps the OIDC mechanism away from the Teams messaging endpoint.
 * <p>
 * The Bot Connector sends {@code Authorization: Bearer <JWT>}, a token issued
 * by the Bot Framework, not by the deployment's identity provider.
 * Authentication is proactive, so without this mechanism the OIDC mechanism
 * would try to verify that bearer and answer 401 before the {@code permit}
 * policy is even consulted, and Teams could never reach the endpoint in a
 * deployment with OIDC on. For exactly
 * {@code POST /integrations/teams/messages} this mechanism, ordered just before
 * OIDC, produces the anonymous identity; the endpoint then verifies the token
 * itself ({@code TeamsTokenVerifier}). Every other path is left to the other
 * mechanisms.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamsWebhookAuthMechanism implements HttpAuthenticationMechanism {

    static final String MESSAGES_PATH = "/integrations/teams/messages";

    private final int priority;

    @Inject
    public TeamsWebhookAuthMechanism(@ConfigProperty(name = "quarkus.oidc.priority", defaultValue = "1001") int oidcPriority) {
        this.priority = oidcPriority == Integer.MAX_VALUE ? Integer.MAX_VALUE : oidcPriority + 1;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (!appliesTo(context)) {
            return Uni.createFrom().nullItem();
        }
        return identityProviderManager.authenticate(HttpSecurityUtils.setRoutingContextAttribute(new AnonymousAuthenticationRequest(), context));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        return Uni.createFrom().nullItem();
    }

    @Override
    public int getPriority() {
        return priority;
    }

    static boolean appliesTo(RoutingContext context) {
        if (context == null || context.request() == null || context.request().method() != HttpMethod.POST) {
            return false;
        }
        String path = context.normalizedPath();
        return path != null && MESSAGES_PATH.equals(HttpSecurityUtils.normalizePath(path)) && MESSAGES_PATH.equals(path);
    }
}
