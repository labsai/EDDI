/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.mongo.ConnectionStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.connections.oauth.CredentialEndpointAllowlist;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.integrations.openai.OpenAiCompatConfig;
import ai.labs.eddi.secrets.ISecretProvider;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start when connections are enabled in a configuration that would
 * make them unsafe or non-functional.
 * <p>
 * Follows the {@code OpenAiStartupGuard} precedent: a misconfiguration that
 * silently stores refresh tokens under self-asserted identities is far more
 * costly than a failed boot, because nothing about the running system looks
 * wrong afterwards.
 */
@ApplicationScoped
public class ConnectionStartupGuard {

    private static final Logger LOGGER = Logger.getLogger(ConnectionStartupGuard.class);

    /**
     * Later than {@code VaultSecretProvider}, which derives the KEK and decides
     * {@code isAvailable()} in its own startup observer. A larger number runs
     * later.
     */
    private static final int CONNECTIONS_GUARD_PRIORITY = Interceptor.Priority.APPLICATION + 500;

    private final ConnectionsConfig connectionsConfig;
    private final CredentialEndpointAllowlist endpointAllowlist;
    private final IConnectionStore connectionStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final ISecretProvider secretProvider;
    private final OpenAiCompatConfig openAiCompatConfig;
    private final boolean authorizationEnabled;

    @Inject
    public ConnectionStartupGuard(ConnectionsConfig connectionsConfig, CredentialEndpointAllowlist endpointAllowlist,
            IConnectionStore connectionStore, IDocumentDescriptorStore descriptorStore, ISecretProvider secretProvider,
            OpenAiCompatConfig openAiCompatConfig,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled) {
        this.connectionsConfig = connectionsConfig;
        this.endpointAllowlist = endpointAllowlist;
        this.connectionStore = connectionStore;
        this.descriptorStore = descriptorStore;
        this.secretProvider = secretProvider;
        this.openAiCompatConfig = openAiCompatConfig;
        this.authorizationEnabled = authorizationEnabled;
    }

    /**
     * Ordered explicitly, because one of the checks below asks the vault whether it
     * is available and the vault decides that in its OWN startup observer. With
     * both unordered, CDI was free to run this first, see an uninitialised vault,
     * and report every OAuth connection as unsupported - on some boots and not
     * others, on the same configuration.
     */
    // CDI requires the @Observes parameter for event discovery; not read directly
    void onStart(@Observes
    @Priority(CONNECTIONS_GUARD_PRIORITY) StartupEvent event) {
        if (!connectionsConfig.isEnabled()) {
            return;
        }

        requirePublicBaseUrl();
        requireStoredConnectionsAreSupportable();

        if (endpointAllowlist.isEmpty()) {
            LOGGER.warn("[CONNECTIONS] eddi.connections.credential-endpoint-allowlist is empty, so no OAuth connection can resolve. "
                    + "STATIC and BASIC connections are unaffected.");
        } else {
            LOGGER.infof("[CONNECTIONS] Enabled. Credential endpoints allowed: %s", endpointAllowlist.origins());
        }
    }

    /**
     * {@code redirect_uri} must match the provider's registration exactly, so EDDI
     * has to know its own public base URL — it cannot derive one from an inbound
     * request without letting a {@code Host} header steer it.
     * <p>
     * Parsed rather than prefix-matched. {@code startsWith("https://")} accepts a
     * path, a query, a fragment, userinfo and a malformed authority, every one of
     * which produces a redirect URI the provider will not match — and the failure
     * surfaces as a user-facing OAuth error, not as a config problem.
     */
    private void requirePublicBaseUrl() {
        String publicBaseUrl = connectionsConfig.getPublicBaseUrl();
        if (publicBaseUrl.isBlank()) {
            throw new IllegalStateException("eddi.connections.enabled=true requires eddi.connections.public-base-url. It becomes the OAuth "
                    + "redirect_uri, which the provider matches exactly, so it cannot be inferred from an inbound request.");
        }
        URI base;
        try {
            base = new URI(publicBaseUrl);
        } catch (Exception e) {
            throw new IllegalStateException("eddi.connections.public-base-url is not a valid URL: " + publicBaseUrl, e);
        }
        if (isDevOrTest()) {
            // http://localhost is the normal shape while developing, and refusing it
            // would make the feature untestable outside a TLS-terminating proxy.
            return;
        }
        boolean bareHttpsOrigin = "https".equals(base.getScheme()) && base.getUserInfo() == null && base.getQuery() == null
                && base.getFragment() == null && (base.getPath() == null || base.getPath().isEmpty() || "/".equals(base.getPath()))
                && base.getHost() != null;
        if (!bareHttpsOrigin) {
            throw new IllegalStateException("eddi.connections.public-base-url must be a bare https origin (scheme://host[:port]) — got: "
                    + publicBaseUrl);
        }
    }

    /**
     * Reports the configurations that make a stored grant meaningless.
     * <p>
     * All are checked against what is actually STORED rather than against a flag,
     * because the dangerous state is "someone created a PER_USER connection on a
     * deployment that has no verified identities" and no configuration property
     * records that.
     * <p>
     * <b>Reports, not refuses.</b> These used to throw, and the consequence was out
     * of all proportion: an administrator saving one connection through the REST
     * API - a live, permitted, single request - left every replica in the cluster
     * unable to boot from that moment on, including the ones that had not restarted
     * yet and so gave no warning. The next rolling restart took the deployment down
     * entirely, over a config document, with the only fix being a direct database
     * edit.
     * <p>
     * Nothing unsafe is permitted by logging instead: every condition fails closed
     * at request time anyway - {@code ConnectionResolver} refuses PER_USER without
     * a verified principal, and the vault refuses to seal a grant when it is
     * inactive. The check that genuinely belongs at boot has been moved to where it
     * is actionable: the write boundary, where the administrator making the change
     * is still there to see the error.
     */
    private void requireStoredConnectionsAreSupportable() {
        List<ConnectionConfiguration> connections = readAll();
        boolean anyPerUser = connections.stream().anyMatch(connection -> connection.getBinding() == Binding.PER_USER);
        boolean anyOAuth = connections.stream()
                .anyMatch(connection -> connection.getAuthType() != null && connection.getAuthType().isOAuth());

        if (anyPerUser && openAiCompatConfig.isEnabled() && !openAiCompatConfig.isOidcMode() && openAiCompatConfig.isTrustUserHeaders()) {
            // Reported even when authorization.enabled=true, because that flag is
            // precisely what this combination makes untrue. The /v1 surface in api-key
            // mode authenticates a SHARED SECRET and then believes whatever
            // X-OpenWebUI-User-Id the caller sent, so a conversation opened there
            // carries a user id nobody authenticated. PER_USER resolution refuses such
            // a conversation at request time; this says at boot which two settings
            // combined to make that happen, so the refusals are not a mystery.
            LOGGER.error("[CONNECTIONS] A PER_USER connection is stored while the OpenAI-compatible /v1 surface is enabled in api-key mode "
                    + "with eddi.openai-compat.trust-user-headers=true. Conversations opened through /v1 then carry a caller-supplied "
                    + "user id, so a holder of the shared api key can open a conversation as any user. Those conversations are REFUSED a "
                    + "PER_USER credential at request time. Set eddi.openai-compat.http-policy=authenticated, or "
                    + "eddi.openai-compat.trust-user-headers=false, or accept the delegation explicitly on the connection.");
        }
        if (anyPerUser && !authorizationEnabled) {
            LOGGER.error("[CONNECTIONS] A PER_USER connection is stored, but authorization.enabled=false. Every resolution of it will be "
                    + "REFUSED at request time, because without a verified identity any caller could claim any userId and resolve that "
                    + "user's tokens (see OpenAiAuthFilter's trust-user-headers caveat). Enable OIDC, or change the connection to SERVICE "
                    + "binding.");
        }
        if (anyOAuth && !secretProvider.isAvailable()) {
            LOGGER.error("[CONNECTIONS] An OAuth connection is stored, but the SecretsVault is inactive (EDDI_VAULT_MASTER_KEY is unset). "
                    + "Every grant it would store or read will be REFUSED at request time: grants are envelope-encrypted with the tenant "
                    + "DEK and there is deliberately no plaintext fallback. This is the one place the autoVaultSecret pattern of degrading "
                    + "to plaintext is not acceptable, because these are refresh tokens.");
        }
    }

    /**
     * Every stored connection, tolerating a store that is not reachable yet.
     * <p>
     * A guard that cannot read the store must not block startup on that basis: the
     * database may simply be starting alongside us, and refusing to boot on a
     * transient read failure converts a slow dependency into an outage. It logs
     * loudly instead, and the per-request checks in {@code ConnectionResolver}
     * still refuse anything unsafe.
     */
    private List<ConnectionConfiguration> readAll() {
        var connections = new ArrayList<ConnectionConfiguration>();
        try {
            List<DocumentDescriptor> descriptors = descriptorStore.readDescriptors(ConnectionStore.RESOURCE_TYPE, "", 0, IDescriptorStore.NO_LIMIT,
                    false);
            if (descriptors == null) {
                return connections;
            }
            for (DocumentDescriptor descriptor : descriptors) {
                ConnectionConfiguration connection = readByDescriptor(descriptor);
                if (connection != null) {
                    connections.add(connection);
                }
            }
        } catch (Exception e) {
            LOGGER.warnf("[CONNECTIONS] Could not enumerate stored connections at startup (%s); the per-request checks still apply.",
                    e.getClass().getSimpleName());
        }
        return connections;
    }

    private ConnectionConfiguration readByDescriptor(DocumentDescriptor descriptor) {
        try {
            URI resource = descriptor.getResource();
            if (resource == null || resource.getPath() == null) {
                return null;
            }
            String path = resource.getPath();
            String id = path.substring(path.lastIndexOf('/') + 1);
            return connectionStore.read(id, versionOf(resource));
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer versionOf(URI resource) {
        String query = resource.getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("version=")) {
                    try {
                        return Integer.valueOf(pair.substring("version=".length()));
                    } catch (NumberFormatException ignored) {
                        return 1;
                    }
                }
            }
        }
        return 1;
    }

    /** Package-private so a test can drive production mode without a container. */
    boolean isDevOrTest() {
        LaunchMode mode = LaunchMode.current();
        return mode == LaunchMode.DEVELOPMENT || mode == LaunchMode.TEST;
    }
}
