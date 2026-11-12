/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Refuses to start when the OpenAI-compatible surface would be exposed without
 * any authentication.
 * <p>
 * Mirrors {@code AuthStartupGuard}: a misconfiguration that silently opens a
 * conversation endpoint is far more costly than a failed boot, because nothing
 * about the running system looks wrong afterwards. The specific combination
 * guarded against is <em>enabled</em> + <em>{@code http-policy=permit}</em>
 * (Quarkus does not authenticate) + <em>no API key</em> (the adapter does not
 * either) + <em>authorization on</em> (the operator expects the deployment to
 * be secured).
 * <p>
 * It also warns when OIDC is on and the adapter is meant to check the shared
 * key itself, but {@link OpenAiPermitModeAuthMechanism} cannot keep OIDC off
 * {@code /v1} — inclusive authentication, a {@code /v1} permission policy
 * overridden to something other than {@code permit}, or an unknown
 * {@code http-policy}. Every shared-key request then fails OIDC token
 * verification with a {@code 401} before the adapter runs. Not a startup
 * failure: nothing is exposed, it just cannot work.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class OpenAiStartupGuard {

    private static final Logger LOGGER = Logger.getLogger(OpenAiStartupGuard.class);

    private final OpenAiCompatConfig config;
    private final boolean authorizationEnabled;
    private final boolean oidcEnabled;
    private final OpenAiPermitModeAuthMechanism permitModeMechanism;

    @Inject
    public OpenAiStartupGuard(OpenAiCompatConfig config,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled,
            @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "true") boolean oidcEnabled,
            OpenAiPermitModeAuthMechanism permitModeMechanism) {
        this.config = config;
        this.authorizationEnabled = authorizationEnabled;
        this.oidcEnabled = oidcEnabled;
        this.permitModeMechanism = permitModeMechanism;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.isEnabled()) {
            return;
        }

        if (isUnprotected()) {
            throw new IllegalStateException("""
                    The OpenAI-compatible API (/v1) is enabled with no authentication, but this \
                    deployment has authorization enabled. Anyone who can reach the port could start \
                    conversations as any user. Fix by one of:
                      * set eddi.openai-compat.api-key=<shared secret>   (Open WebUI setup), or
                      * set eddi.openai-compat.http-policy=authenticated (validate OIDC tokens), or
                      * set eddi.openai-compat.enabled=false             (turn the surface off).""");
        }

        if (!config.isOidcMode() && config.isTrustUserHeaders()) {
            LOGGER.infof("OpenAI-compatible API enabled on /v1 (api-key auth). "
                    + "X-OpenWebUI-User-Id is trusted as the EDDI userId — a leaked api-key therefore "
                    + "permits impersonating any user. Set eddi.openai-compat.trust-user-headers=false to disable.");
        } else {
            LOGGER.infof("OpenAI-compatible API enabled on /v1 (policy=%s).", config.getHttpPolicy());
        }

        if (isSharedKeyBlockedByOidc()) {
            LOGGER.warnf("""
                    The OpenAI-compatible API (/v1) checks the shared api-key itself \
                    (eddi.openai-compat.http-policy=%s), but OIDC is on and cannot be kept off /v1: \
                    quarkus.http.auth.inclusive is true, quarkus.http.auth.permission.openai-compat.policy \
                    is not 'permit', or http-policy is neither 'permit' nor 'authenticated'. Every request \
                    carrying the api-key will fail OIDC token verification with 401 before the adapter runs. \
                    Restore http-policy=permit with the shipped openai-compat permission and inclusive \
                    authentication off, or switch to http-policy=authenticated.""", config.getHttpPolicy());
        }

        if (config.isAllowAnonymous()) {
            LOGGER.warnf("eddi.openai-compat.allow-anonymous=true — every caller without a resolvable "
                    + "identity shares one conversation (and its memory) per agent and chat. "
                    + "This is intended for single-user deployments only.");
        }
    }

    /** Neither Quarkus nor the adapter would authenticate a caller. */
    private boolean isUnprotected() {
        return authorizationEnabled && !config.isOidcMode() && !config.hasApiKey();
    }

    /**
     * OIDC would verify the shared key as a token: the adapter is in key mode, OIDC
     * is on, and the permit-mode mechanism is not active.
     */
    boolean isSharedKeyBlockedByOidc() {
        return config.isEnabled() && oidcEnabled && !config.isOidcMode()
                && (permitModeMechanism == null || !permitModeMechanism.isActive());
    }
}
