/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.integrations.channels.types.EmailChannelType;
import ai.labs.eddi.integrations.channels.types.SlackChannelType;
import ai.labs.eddi.integrations.channels.types.WebhookChannelType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The channel types this deployment knows, discovered as
 * {@link ChannelTypeDescriptor} beans. Replaces the hard-coded set of type
 * names the channel store used to validate against.
 * <p>
 * Also the one place channel configuration refusals are counted:
 * {@code eddi_channel_config_rejected_total{type,reason}} for saves refused,
 * {@code eddi_channel_config_keys_dropped_total{type}} for undeclared keys the
 * router strips from a document that bypassed save validation, and
 * {@code eddi_channel_credential_refused_total{type,reason}} for sends whose
 * connection could not be resolved.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class ChannelTypeRegistry {

    private final Map<String, ChannelTypeDescriptor> byId;

    /**
     * {@code null} outside CDI (tests, {@link #builtIns()}): counting is then a
     * no-op.
     */
    private final MeterRegistry meterRegistry;

    @Inject
    public ChannelTypeRegistry(Instance<ChannelTypeDescriptor> descriptors, Instance<MeterRegistry> meterRegistry) {
        this(descriptors.stream().toList(), meterRegistry != null && meterRegistry.isResolvable() ? meterRegistry.get() : null);
    }

    public ChannelTypeRegistry(Collection<? extends ChannelTypeDescriptor> descriptors) {
        this(descriptors, null);
    }

    /**
     * @throws IllegalStateException
     *             when two descriptors claim one id, or one has a blank id: either
     *             would make validation and routing depend on bean order
     */
    public ChannelTypeRegistry(Collection<? extends ChannelTypeDescriptor> descriptors, MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        var map = new TreeMap<String, ChannelTypeDescriptor>();
        for (ChannelTypeDescriptor descriptor : descriptors) {
            String id = descriptor.id();
            if (id == null || id.isBlank() || !id.equals(id.toLowerCase(Locale.ROOT)) || id.contains(":")) {
                // ':' separates the type from the key in the router's index.
                throw new IllegalStateException("Channel type descriptor " + descriptor.getClass().getName()
                        + " has an id that is blank, not lower-case or contains ':': '" + id + "'");
            }
            if (descriptor.connectionBacked() && descriptor.allowedPlatformConfigKeys().isEmpty()) {
                throw new IllegalStateException("Channel type '" + id + "' is connection-backed but declares no allowed platformConfig keys");
            }
            if (descriptor.connectionBacked() && !descriptor.allowedPlatformConfigKeys().get().contains(ChannelTypeDescriptor.KEY_CONNECTION)) {
                // Otherwise every save is refused twice over: the key is not allowed, and
                // it is required.
                throw new IllegalStateException("Channel type '" + id + "' is connection-backed but does not allow the platformConfig key '"
                        + ChannelTypeDescriptor.KEY_CONNECTION + "'");
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

    /** Counts a save refused for {@code type}, by a short stable {@code reason}. */
    public void recordRejected(String type, String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi_channel_config_rejected_total", "type", typeTag(type), "reason", reasonTag(reason)).increment();
        }
    }

    /** Counts a send whose connection could not be resolved. */
    public void recordCredentialRefused(String type, String reason) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi_channel_credential_refused_total", "type", typeTag(type), "reason", reasonTag(reason)).increment();
        }
    }

    /** Counts an undeclared key the router dropped from a stored document. */
    public void recordDroppedKey(String type) {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi_channel_config_keys_dropped_total", "type", typeTag(type)).increment();
        }
    }

    /**
     * A bounded type tag: a registered type id, else {@code other}, so a stored
     * typo or a raw request value cannot add series.
     */
    private String typeTag(String type) {
        if (type == null) {
            return "unknown";
        }
        String lower = type.toLowerCase(Locale.ROOT);
        return byId.containsKey(lower) ? lower : "other";
    }

    /**
     * A bounded reason tag. Callers pass constants or enum names; anything else
     * collapses to {@code other}.
     */
    private static String reasonTag(String reason) {
        if (reason == null) {
            return "unknown";
        }
        String lower = reason.toLowerCase(Locale.ROOT);
        return lower.matches("[a-z_]{1,40}") ? lower : "other";
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
            // Sorted for a stable message order; a null key (no JSON produces one) is
            // skipped before sorting, which would otherwise throw.
            List<String> keys = pc.keySet().stream().filter(Objects::nonNull).sorted().toList();
            for (String key : keys) {
                String value = pc.get(key);
                if (allowed.contains(key)) {
                    // The router never resolves a vault reference for this type, so one
                    // here would be stored and used as a literal identifier.
                    if (value != null && value.contains("${vault:")) {
                        errors.add("platformConfig." + key + " must not be a vault reference: a " + descriptor.id()
                                + " integration holds no secrets, and its settings are used as written.");
                    }
                    continue;
                }
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
            } else if (ChannelConnectionCredentials.connectionOf(pc).isEmpty()) {
                errors.add("platformConfig.connection must be a connection name (letters, digits, '.', '_', '-'), a workspace's "
                        + "connection as <tenant>/<name>, or either wrapped as ${connection:...}.");
            }
        }
        List<String> typeErrors = descriptor.validate(pc);
        errors.addAll(typeErrors);
        // Routing keys are derived from the type's own fields, so they are judged only
        // once those fields are valid: a missing appId must be reported as such, not
        // surface as an exception from the key derivation.
        if (descriptor.connectionBacked() && typeErrors.isEmpty()) {
            errors.addAll(routingKeyErrors(descriptor, pc));
        }
        return errors;
    }

    /**
     * Every key the type is routed by, not just {@code channelId}: a type may route
     * by another key (a Teams conversationId), and one in the application namespace
     * other than this integration's own would claim another application's direct
     * messages. Its own application key is allowed only on a channel-less
     * integration, the one that answers that application's DMs.
     */
    private static List<String> routingKeyErrors(ChannelTypeDescriptor descriptor, Map<String, String> pc) {
        List<String> errors = new ArrayList<>();
        String channelId = pc.get(ChannelTypeDescriptor.KEY_CHANNEL_ID);
        boolean channelBound = channelId != null && !channelId.isBlank();
        String ownApplicationKey = descriptor.applicationId(pc).filter(id -> !id.isBlank())
                .map(ChannelTypeDescriptor::applicationKey).orElse(null);
        List<String> routingKeys;
        try {
            routingKeys = descriptor.routingKeys(null, pc);
        } catch (RuntimeException e) {
            errors.add("The " + descriptor.id() + " routing keys could not be derived from platformConfig: " + e.getMessage());
            return errors;
        }
        for (String routingKey : routingKeys) {
            if (routingKey == null || !routingKey.startsWith("@") || routingKey.equals(channelId)) {
                continue;
            }
            if (!routingKey.equals(ownApplicationKey)) {
                errors.add("A " + descriptor.id() + " routing key must not start with '@': '" + routingKey + "'.");
            } else if (channelBound) {
                errors.add("A " + descriptor.id() + " integration bound to channel '" + channelId + "' must not be routed by its "
                        + "application key: only the channel-less integration answers the application's direct messages.");
            }
        }
        return errors;
    }
}
