/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.mongo.ConnectionStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.connections.oauth.CredentialEndpointAllowlist;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.secrets.ISecretProvider;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
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

    private final ConnectionsConfig connectionsConfig;
    private final CredentialEndpointAllowlist endpointAllowlist;
    private final IConnectionStore connectionStore;
    private final IDocumentDescriptorStore descriptorStore;
    private final ISecretProvider secretProvider;
    private final boolean authorizationEnabled;

    @Inject
    public ConnectionStartupGuard(ConnectionsConfig connectionsConfig, CredentialEndpointAllowlist endpointAllowlist,
            IConnectionStore connectionStore, IDocumentDescriptorStore descriptorStore, ISecretProvider secretProvider,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled) {
        this.connectionsConfig = connectionsConfig;
        this.endpointAllowlist = endpointAllowlist;
        this.connectionStore = connectionStore;
        this.descriptorStore = descriptorStore;
        this.secretProvider = secretProvider;
        this.authorizationEnabled = authorizationEnabled;
    }

    // CDI requires the @Observes parameter for event discovery; not read directly
    void onStart(@Observes StartupEvent event) {
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
     * Refuses the two configurations that make a stored grant meaningless.
     * <p>
     * Both are checked against what is actually STORED rather than against a flag,
     * because the dangerous state is "someone created a PER_USER connection on a
     * deployment that has no verified identities" and no configuration property
     * records that.
     */
    private void requireStoredConnectionsAreSupportable() {
        List<ConnectionConfiguration> connections = readAll();
        boolean anyPerUser = connections.stream().anyMatch(connection -> connection.getBinding() == Binding.PER_USER);
        boolean anyOAuth = connections.stream()
                .anyMatch(connection -> connection.getAuthType() == AuthType.OAUTH2_AUTHORIZATION_CODE
                        || connection.getAuthType() == AuthType.OAUTH2_CLIENT_CREDENTIALS);

        if (anyPerUser && !authorizationEnabled) {
            throw new IllegalStateException("PER_USER connections require authorization.enabled=true — without a verified identity, any caller "
                    + "can claim any userId and resolve that user's tokens (see OpenAiAuthFilter's trust-user-headers caveat). Enable OIDC, "
                    + "or change the connection to SERVICE binding.");
        }
        if (anyOAuth && !secretProvider.isAvailable()) {
            throw new IllegalStateException("OAuth connections require an active SecretsVault (EDDI_VAULT_MASTER_KEY) — grants are "
                    + "envelope-encrypted with the tenant DEK and there is deliberately no plaintext fallback. This is the one place the "
                    + "autoVaultSecret pattern of degrading to plaintext is not acceptable: these are refresh tokens.");
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
