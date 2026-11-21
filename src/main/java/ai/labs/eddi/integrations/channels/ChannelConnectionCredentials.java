/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.ResolvedCredential;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Credentials of a connection-backed channel type
 * ({@link ChannelTypeDescriptor#connectionBacked()}): the integration names an
 * EDDI connection in {@code platformConfig.connection} and holds no secret
 * itself.
 * <p>
 * The credential is resolved through {@link ConnectionResolver} on every send,
 * with the real destination, so the connection's {@code baseUrlAllowlist} is
 * checked against where the token is actually going (a Teams {@code serviceUrl}
 * arrives with each activity). Nothing here caches a resolved value: the
 * connection layer already caches and refreshes tokens single-flight, and a
 * token kept next to the router's 60-second configuration cache would outlive a
 * rotation. The connection is bound {@code SERVICE} and resolved with no
 * principal — an inbound chat event runs as no verified EDDI user.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ChannelConnectionCredentials {

    private final Instance<ConnectionResolver> connectionResolver;

    @Inject
    public ChannelConnectionCredentials(Instance<ConnectionResolver> connectionResolver) {
        this.connectionResolver = connectionResolver;
    }

    /**
     * The {@code ${connection:name}} reference an integration's configuration
     * names, or {@code null}.
     */
    public static String connectionReference(Map<String, String> platformConfig) {
        String raw = platformConfig == null ? null : platformConfig.get(ChannelTypeDescriptor.KEY_CONNECTION);
        String name = EmailChannelSettings.connectionName(raw);
        return name == null ? null : "${connection:" + name + "}";
    }

    /**
     * Save-time errors of a connection-backed integration's connection: it exists,
     * is {@code SERVICE}-bound, has an auth type the channel type accepts and, when
     * the type has a fixed {@link ChannelTypeDescriptor#apiOrigin()}, may be sent
     * there. Empty for a type that is not connection-backed, or when the name
     * itself is malformed (reported by
     * {@link ChannelTypeRegistry#validatePlatformConfig}).
     */
    public List<String> validateForSave(ChannelTypeDescriptor descriptor, Map<String, String> platformConfig) {
        if (!descriptor.connectionBacked()) {
            return List.of();
        }
        String reference = connectionReference(platformConfig);
        if (reference == null) {
            return List.of();
        }
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            return List.of("Connections are not available in this deployment, so platformConfig.connection cannot be checked.");
        }
        URI origin = descriptor.apiOrigin().orElse(null);
        try {
            connectionResolver.get().requireServiceTarget(reference, origin, descriptor.acceptedConnectionAuthTypes());
            var identity = descriptor.requiredConnectionIdentity(platformConfig);
            if (identity.isPresent()) {
                ConnectionIdentityRequirement required = identity.get();
                connectionResolver.get().proveServiceIdentity(reference, required.clientId(), required.tokenUrl(), required.scope());
            }
            return List.of();
        } catch (ConnectionException e) {
            if (e.getReason() == ConnectionException.Reason.TARGET_NOT_ALLOWED && origin != null) {
                return List.of("platformConfig.connection: " + e.getMessage() + " The connection's baseUrlAllowlist must contain " + origin
                        + ".");
            }
            return List.of("platformConfig.connection: " + e.getMessage());
        }
    }

    /**
     * Whether {@code integration}'s connection still holds the identity its type
     * demands ({@link ChannelTypeDescriptor#requiredConnectionIdentity}), checked
     * without a network call. An inbound adapter calls this before trusting the
     * integration with a verified application's traffic: save validation is skipped
     * by a ZIP import or a direct store write, and the connection can be edited
     * after the save. {@code true} for a type that demands no identity.
     */
    public boolean holdsRequiredIdentity(ChannelTypeDescriptor descriptor, ChannelIntegrationConfiguration integration) {
        if (descriptor == null || integration == null) {
            return false;
        }
        var identity = descriptor.requiredConnectionIdentity(integration.getPlatformConfig());
        if (identity.isEmpty()) {
            return true;
        }
        String reference = connectionReference(integration.getPlatformConfig());
        if (reference == null || connectionResolver == null || !connectionResolver.isResolvable()) {
            return false;
        }
        ConnectionIdentityRequirement required = identity.get();
        try {
            connectionResolver.get().requireServiceIdentity(reference, required.clientId(), required.tokenUrl(), required.scope());
            return true;
        } catch (ConnectionException e) {
            return false;
        }
    }

    /**
     * The credential header for one outbound call of {@code integration} to
     * {@code target}. Resolve it immediately before the call and drop it after;
     * never store it.
     *
     * @throws ConnectionException
     *             when the integration names no usable connection, the connection
     *             may not be sent to {@code target}, or it cannot be resolved
     */
    public ResolvedCredential resolve(ChannelIntegrationConfiguration integration, URI target) {
        String reference = integration == null ? null : connectionReference(integration.getPlatformConfig());
        if (reference == null) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "The channel integration names no connection in platformConfig.connection.");
        }
        if (connectionResolver == null || !connectionResolver.isResolvable()) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "Connections are not available in this deployment.");
        }
        return connectionResolver.get().resolve(reference, target, null);
    }
}
