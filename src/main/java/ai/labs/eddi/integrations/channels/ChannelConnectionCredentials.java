/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.ForbiddenException;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Credentials of a connection-backed channel type
 * ({@link ChannelTypeDescriptor#connectionBacked()}): the integration names an
 * EDDI connection in {@code platformConfig.connection} and holds no secret
 * itself.
 * <p>
 * <b>At save time</b> ({@link #validateForSave}) the caller must be allowed to
 * use the connection — a channel acts with its credential, so naming a
 * connection is using it, exactly as aiming a channel at an agent is (see
 * {@code RestChannelIntegrationStore.requireUseOnTargets}). A connection that
 * does not exist and one the caller may not use get the same answer, so the
 * check cannot be used to discover other people's connections. Then the
 * connection must be {@code SERVICE}-bound, of an accepted auth type, allowed
 * to reach the type's API origin and, for a type whose application id is its
 * OAuth client id, be that application's.
 * <p>
 * <b>On every send</b> ({@link #resolve}) the credential is resolved through
 * {@link ConnectionResolver#resolveService}, with the real destination and the
 * binding and auth type re-checked: the connection may have been edited since
 * the integration was saved, and nothing here caches a resolved value — the
 * connection layer already caches and refreshes tokens single-flight, and a
 * token kept next to the router's 60-second configuration cache would outlive a
 * rotation. It is resolved with no principal: an inbound chat event runs as no
 * verified EDDI user.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ChannelConnectionCredentials {

    private static final Logger LOGGER = Logger.getLogger(ChannelConnectionCredentials.class);

    /** One segment of a connection reference: a tenant id or a name. */
    private static final Pattern SEGMENT = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    private static final String REFERENCE_PREFIX = "${connection:";

    private final Instance<ConnectionResolver> connectionResolver;
    private final ChannelTypeRegistry channelTypes;
    private final IConnectionStore connectionStore;
    private final ResourceAccessGuard accessGuard;

    @Inject
    public ChannelConnectionCredentials(Instance<ConnectionResolver> connectionResolver, ChannelTypeRegistry channelTypes,
            IConnectionStore connectionStore, ResourceAccessGuard accessGuard) {
        this.connectionResolver = connectionResolver;
        this.channelTypes = channelTypes != null ? channelTypes : ChannelTypeRegistry.builtIns();
        this.connectionStore = connectionStore;
        this.accessGuard = accessGuard;
    }

    /**
     * The connection {@code platformConfig.connection} names: a bare name,
     * {@code tenant/name} for a workspace's connection, or the same wrapped as
     * {@code ${connection:…}}. Empty when absent or malformed.
     */
    public static Optional<ConnectionReference> connectionOf(Map<String, String> platformConfig) {
        String raw = platformConfig == null ? null : platformConfig.get(ChannelTypeDescriptor.KEY_CONNECTION);
        if (raw == null) {
            return Optional.empty();
        }
        String value = raw.trim();
        if (value.startsWith(REFERENCE_PREFIX) && value.endsWith("}")) {
            value = value.substring(REFERENCE_PREFIX.length(), value.length() - 1).trim();
        }
        String[] parts = value.split("/", -1);
        if (parts.length > 2) {
            return Optional.empty();
        }
        for (String part : parts) {
            if (!SEGMENT.matcher(part).matches()) {
                return Optional.empty();
            }
        }
        return Optional.of(parts.length == 2
                ? new ConnectionReference(parts[0], parts[1])
                : new ConnectionReference(ConnectionReference.DEFAULT_TENANT, parts[0]));
    }

    /**
     * The {@code ${connection:…}} reference an integration's configuration names,
     * or {@code null}.
     */
    public static String connectionReference(Map<String, String> platformConfig) {
        return connectionOf(platformConfig).map(ConnectionReference::toReferenceString).orElse(null);
    }

    /**
     * Save-time errors of a connection-backed integration's connection (see the
     * class comment). Empty for a type that is not connection-backed, or when the
     * name itself is malformed (reported by
     * {@link ChannelTypeRegistry#validatePlatformConfig}).
     */
    public List<String> validateForSave(ChannelTypeDescriptor descriptor, Map<String, String> platformConfig) {
        if (!descriptor.connectionBacked()) {
            return List.of();
        }
        Optional<ConnectionReference> parsed = connectionOf(platformConfig);
        if (parsed.isEmpty()) {
            return List.of();
        }
        ConnectionReference connection = parsed.get();
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            return reject(descriptor, "connections_unavailable",
                    "Connections are not available in this deployment, so platformConfig.connection cannot be checked.");
        }
        String usable = requireMayUse(connection);
        if (usable != null) {
            return reject(descriptor, "connection_not_usable", usable);
        }
        String reference = connection.toReferenceString();
        URI origin = descriptor.apiOrigin().orElse(null);
        ConnectionResolver resolver = connectionResolver.get();
        try {
            resolver.requireServiceTarget(reference, origin, descriptor.acceptedConnectionAuthTypes());
        } catch (ConnectionException e) {
            if (e.getReason() == ConnectionException.Reason.TARGET_NOT_ALLOWED && origin != null) {
                return reject(descriptor, "connection_target", "platformConfig.connection: " + e.getMessage()
                        + " The connection's baseUrlAllowlist must contain " + origin + ".");
            }
            return reject(descriptor, "connection_invalid", "platformConfig.connection: " + e.getMessage());
        }
        if (descriptor.applicationIdIsConnectionClientId()) {
            Optional<String> applicationId = descriptor.applicationId(platformConfig).filter(id -> !id.isBlank());
            Optional<String> clientId = resolver.oauthClientIdOf(reference);
            if (applicationId.isPresent() && !clientId.filter(applicationId.get()::equals).isPresent()) {
                return reject(descriptor, "application_not_owned", "The " + descriptor.id() + " application id must be the OAuth client id "
                        + "of the connection in platformConfig.connection: that is the proof that this integration holds the "
                        + "application's credential and may receive its messages.");
            }
        }
        return List.of();
    }

    /**
     * {@code null} when the caller may use the connection, else the one message a
     * missing connection and a forbidden one share.
     */
    private String requireMayUse(ConnectionReference connection) {
        String uniform = "platformConfig.connection: no usable connection '" + connection.name() + "' — it does not exist, or you "
                + "may not use it. Ask its owner to share it with you, or create your own.";
        String id;
        try {
            id = connectionStore == null ? null : connectionStore.idOfName(connection.tenantId(), connection.name());
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.warnf("Could not look up connection '%s' for a channel save: %s", sanitize(connection.name()), e.getMessage());
            return "platformConfig.connection could not be checked right now. Please retry.";
        }
        if (id == null) {
            return uniform;
        }
        if (accessGuard != null) {
            try {
                accessGuard.requireUseAccess(id, "connection");
            } catch (ForbiddenException e) {
                return uniform;
            }
        }
        return null;
    }

    private List<String> reject(ChannelTypeDescriptor descriptor, String reason, String message) {
        channelTypes.recordRejected(descriptor.id(), reason);
        return List.of(message);
    }

    /**
     * The credential header for one outbound call of {@code integration} to
     * {@code target}. Resolve it immediately before the call and drop it after;
     * never store it.
     *
     * @throws ConnectionException
     *             when the integration is not of a connection-backed type, names no
     *             usable connection, the connection is no longer
     *             {@code SERVICE}-bound or of an accepted auth type, may not be
     *             sent to {@code target}, or cannot be resolved
     */
    public ResolvedCredential resolve(ChannelIntegrationConfiguration integration, URI target) {
        if (integration == null) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "No channel integration to resolve for.");
        }
        ChannelTypeDescriptor descriptor = channelTypes.find(integration.getChannelType())
                .filter(ChannelTypeDescriptor::connectionBacked)
                .orElseThrow(() -> new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                        "Channel type '" + integration.getChannelType() + "' does not take its credential from a connection."));
        String reference = connectionReference(integration.getPlatformConfig());
        if (reference == null) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "The channel integration names no connection in platformConfig.connection.");
        }
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "Connections are not available in this deployment.");
        }
        try {
            return connectionResolver.get().resolveService(reference, target, descriptor.acceptedConnectionAuthTypes());
        } catch (ConnectionException e) {
            channelTypes.recordCredentialRefused(descriptor.id(), e.getReason().name().toLowerCase(Locale.ROOT));
            throw e;
        }
    }
}
