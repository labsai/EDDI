/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.integrations.channels.types.EmailChannelType;
import ai.labs.eddi.integrations.channels.types.SlackChannelType;
import ai.labs.eddi.integrations.channels.types.WebhookChannelType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The channel types this deployment knows, discovered as
 * {@link ChannelTypeDescriptor} beans. Replaces the hard-coded set of type
 * names the channel store used to validate against.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ChannelTypeRegistry {

    private final Map<String, ChannelTypeDescriptor> byId;

    @Inject
    public ChannelTypeRegistry(Instance<ChannelTypeDescriptor> descriptors) {
        this(descriptors.stream().toList());
    }

    /**
     * @throws IllegalStateException
     *             when two descriptors claim one id, or one has a blank id: either
     *             would make validation and routing depend on bean order
     */
    public ChannelTypeRegistry(Collection<? extends ChannelTypeDescriptor> descriptors) {
        var map = new TreeMap<String, ChannelTypeDescriptor>();
        for (ChannelTypeDescriptor descriptor : descriptors) {
            String id = descriptor.id();
            if (id == null || id.isBlank() || !id.equals(id.toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException("Channel type descriptor " + descriptor.getClass().getName()
                        + " has an id that is blank or not lower-case: '" + id + "'");
            }
            if (descriptor.connectionBacked() && descriptor.allowedPlatformConfigKeys().isEmpty()) {
                throw new IllegalStateException("Channel type '" + id + "' is connection-backed but declares no allowed platformConfig keys");
            }
            ChannelTypeDescriptor previous = map.putIfAbsent(id, descriptor);
            if (previous != null) {
                throw new IllegalStateException("Channel type '" + id + "' is registered twice: " + previous.getClass().getName() + " and "
                        + descriptor.getClass().getName());
            }
        }
        this.byId = Map.copyOf(map);
    }

    /**
     * The built-in types ({@code slack}, {@code webhook}, {@code email}), for code
     * constructed outside CDI — tests and the shorter constructors.
     */
    public static ChannelTypeRegistry builtIns() {
        return new ChannelTypeRegistry(List.of(new SlackChannelType(), new WebhookChannelType(), new EmailChannelType()));
    }

    /** The descriptor of {@code channelType} (case-insensitive), if registered. */
    public Optional<ChannelTypeDescriptor> find(String channelType) {
        if (channelType == null) {
            return Optional.empty();
        }
        // Not trimmed: everything downstream compares the stored string, so " email"
        // must be refused here rather than validated as email and then never polled.
        return Optional.ofNullable(byId.get(channelType.toLowerCase(Locale.ROOT)));
    }

    /** The registered type ids, sorted. */
    public Set<String> ids() {
        return new TreeSet<>(byId.keySet());
    }

    /**
     * The routing keys of an integration: its descriptor's, or for a type no
     * descriptor claims (a document stored by an adapter that is no longer
     * deployed) the plain {@code channelId}, which is how such documents were
     * indexed before descriptors existed.
     */
    public List<String> routingKeys(String channelType, String resourceId, Map<String, String> platformConfig) {
        return find(channelType).map(d -> d.routingKeys(resourceId, platformConfig)).orElseGet(() -> {
            String channelId = platformConfig == null ? null : platformConfig.get(ChannelTypeDescriptor.KEY_CHANNEL_ID);
            return channelId == null || channelId.isBlank() ? List.of() : List.of(channelId);
        });
    }

    /**
     * Every save-time error of a {@code platformConfig} that needs no deployment
     * state: for a connection-backed type the allow-list of keys and the connection
     * name, then the type's own {@link ChannelTypeDescriptor#validate}. Whether the
     * named connection exists and may reach the type's origin is checked by
     * {@link ChannelConnectionCredentials#validateForSave}.
     */
    public static List<String> validatePlatformConfig(ChannelTypeDescriptor descriptor, Map<String, String> platformConfig) {
        List<String> errors = new ArrayList<>();
        Map<String, String> pc = platformConfig == null ? Map.of() : platformConfig;
        if (descriptor.connectionBacked()) {
            Set<String> allowed = descriptor.allowedPlatformConfigKeys().orElse(Set.of());
            for (var entry : new TreeMap<>(pc).entrySet()) {
                String key = entry.getKey();
                if (key == null || allowed.contains(key)) {
                    continue;
                }
                String value = entry.getValue();
                if (value != null && value.contains("${vault:")) {
                    errors.add("platformConfig." + key + " is not a " + descriptor.id() + " setting, and a " + descriptor.id()
                            + " integration holds no secrets, not even as a vault reference: put the credential in an EDDI "
                            + "connection and name it in platformConfig.connection.");
                } else {
                    errors.add("platformConfig." + key + " is not a " + descriptor.id() + " setting. Allowed: " + new TreeSet<>(allowed)
                            + ". Credentials never go into platformConfig: they live in the EDDI connection named by "
                            + "platformConfig.connection.");
                }
            }
            String channelId = pc.get(ChannelTypeDescriptor.KEY_CHANNEL_ID);
            if (channelId != null && channelId.startsWith("@")) {
                // '@' starts the router's application keys; a channel id that looked
                // like one could take over an application's DM route.
                errors.add("platformConfig.channelId must not start with '@'.");
            }
            String connection = pc.get(ChannelTypeDescriptor.KEY_CONNECTION);
            if (connection == null || connection.isBlank()) {
                errors.add("A " + descriptor.id() + " integration requires platformConfig.connection: the name of the EDDI connection "
                        + "that holds its credentials.");
            } else if (EmailChannelSettings.connectionName(connection) == null) {
                errors.add("platformConfig.connection must be a connection name (letters, digits, '.', '_', '-') or ${connection:<name>}.");
            }
        }
        errors.addAll(descriptor.validate(pc));
        return errors;
    }
}
