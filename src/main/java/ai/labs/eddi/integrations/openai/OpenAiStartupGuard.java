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
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class OpenAiStartupGuard {

    private static final Logger LOGGER = Logger.getLogger(OpenAiStartupGuard.class);

    private final OpenAiCompatConfig config;
    private final boolean authorizationEnabled;

    @Inject
    public OpenAiStartupGuard(OpenAiCompatConfig config,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled) {
        this.config = config;
        this.authorizationEnabled = authorizationEnabled;
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
}
