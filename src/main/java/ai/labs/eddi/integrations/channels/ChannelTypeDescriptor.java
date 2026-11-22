/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.channels;

import ai.labs.eddi.configs.connections.model.AuthType;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One channel type ({@code slack}, {@code webhook}, {@code email}, and later
 * {@code teams} or {@code discord}): what a {@code platformConfig} of that type
 * may hold, how it is validated, where its outbound calls go and under which
 * keys the {@link ChannelTargetRouter} indexes it.
 * <p>
 * Each type is an {@code @ApplicationScoped} CDI bean;
 * {@link ChannelTypeRegistry} discovers them, so adding an adapter no longer
 * means editing a hard-coded set in the channel store.
 *
 * <h2>Two shapes of type</h2>
 * <ul>
 * <li><b>Legacy types</b> ({@code slack}, {@code webhook}, {@code email})
 * return an empty {@link #allowedPlatformConfigKeys()}: their stored documents
 * may carry keys this release does not know, and refusing them on the next
 * update would break integrations that work today. Slack keeps its
 * {@code botToken} / {@code signingSecret} handling in the router.</li>
 * <li><b>Connection-backed types</b> ({@link #connectionBacked()} true) hold no
 * secret at all. {@code platformConfig} carries ids and options only, every key
 * outside {@link #allowedPlatformConfigKeys()} is refused at save time (a
 * pasted token as much as a {@code ${vault:...}} reference), and the credential
 * comes from the EDDI connection named in {@code platformConfig.connection},
 * resolved per send by {@link ChannelConnectionCredentials}. This is an
 * allow-list of non-secret keys, never a list of credential names.</li>
 * </ul>
 *
 * @since 6.7.0
 */
public interface ChannelTypeDescriptor {

    /**
     * The {@code platformConfig} key naming the connection of a connection-backed
     * type.
     */
    String KEY_CONNECTION = "connection";

    /** The key most chat types are routed by. */
    String KEY_CHANNEL_ID = "channelId";

    /**
     * Prefix of an application-level routing key. Channel ids of the supported
     * platforms never start with {@code @}. A connection-backed type refuses, at
     * save time, a {@code channelId} that does and any routing key in this
     * namespace other than the integration's own application key; the router never
     * answers a channel lookup with an application key; and type ids may not
     * contain {@code :}. So the two cannot collide.
     */
    String APPLICATION_KEY_PREFIX = "@app:";

    /** The lower-case type id stored in {@code channelType}. */
    String id();

    /**
     * Type-specific validation of a {@code platformConfig}. Errors refuse the save.
     * The generic allow-list and connection checks run separately (see
     * {@link ChannelTypeRegistry#validatePlatformConfig}).
     */
    default List<String> validate(Map<String, String> platformConfig) {
        return List.of();
    }

    /**
     * Where this type's outbound calls go, e.g. {@code https://discord.com}: the
     * connection's {@code baseUrlAllowlist} must cover it, checked at save time.
     * Empty when the destination is not fixed (the Teams {@code serviceUrl} comes
     * with each activity) or the type makes no HTTP calls.
     */
    default Optional<URI> apiOrigin() {
        return Optional.empty();
    }

    /**
     * The {@code platformConfig} keys a connection-backed type accepts — ids and
     * options, none of them secret. Empty for a legacy type, which accepts any key.
     */
    default Optional<Set<String>> allowedPlatformConfigKeys() {
        return Optional.empty();
    }

    /**
     * Whether credentials come from the connection named in
     * {@code platformConfig.connection}. Such a type must declare
     * {@link #allowedPlatformConfigKeys()}, and the router never resolves a vault
     * reference in its configuration.
     */
    default boolean connectionBacked() {
        return false;
    }

    /**
     * The connection auth types this type can use (Teams: OAuth client credentials;
     * Discord: a STATIC {@code Bot} header). Empty means any.
     */
    default Set<AuthType> acceptedConnectionAuthTypes() {
        return Set.of();
    }

    /** Whether observe-mode targets make sense for this type. */
    default boolean supportsObserveMode() {
        return true;
    }

    /**
     * The verified application identity an integration of this type answers for
     * (the Teams bot app id, the Discord application id), read from its
     * {@code platformConfig}. Used to match an inbound request to its integration
     * when there is no channel to route by. Empty for types authenticated by a
     * shared secret, such as Slack.
     */
    default Optional<String> applicationId(Map<String, String> platformConfig) {
        return Optional.empty();
    }

    /**
     * Whether the {@link #applicationId} must equal the OAuth client id of the
     * connection the integration names. This ties the application to a connection
     * an administrator configured with that client id and the author may use; it
     * does not prove the client secret, which connection saves never test. A
     * channel-less integration, which claims the application's direct messages,
     * additionally needs edit access to that connection. A Teams bot's app id IS
     * its Entra client id; a type that proves its application another way (Discord
     * verifies requests with the application's public key) leaves this
     * {@code false} and must check ownership in its own {@link #validate}.
     */
    default boolean applicationIdIsConnectionClientId() {
        return false;
    }

    /**
     * The keys the router indexes an integration under, and the keys the save-time
     * uniqueness check compares: no two integrations of one type may share one.
     * <p>
     * The default is {@code channelId} when set, otherwise the application key of
     * {@link #applicationId} — so an integration without a channel (a Teams bot
     * serving personal chats) is still routed — otherwise nothing, in which case
     * the integration is not indexed (a Slack integration without a channel, as
     * before). A key in the {@link #APPLICATION_KEY_PREFIX} namespace other than
     * this integration's own application key is refused at save time.
     *
     * @param resourceId
     *            the integration's resource id when the router indexes it;
     *            {@code null} for the save-time uniqueness check, where a type
     *            routed by its own resource id (webhook) has nothing to collide on
     *            and must return no keys
     */
    default List<String> routingKeys(String resourceId, Map<String, String> platformConfig) {
        List<String> keys = new ArrayList<>(1);
        String channelId = platformConfig == null ? null : platformConfig.get(KEY_CHANNEL_ID);
        if (channelId != null && !channelId.isBlank()) {
            keys.add(channelId);
        } else {
            applicationId(platformConfig).filter(id -> !id.isBlank()).ifPresent(id -> keys.add(applicationKey(id)));
        }
        return keys;
    }

    /**
     * The routing key of an application identity.
     *
     * @throws IllegalArgumentException
     *             for a null or blank id, which would otherwise become the key
     *             {@code @app:null} or {@code @app:}
     */
    static String applicationKey(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            throw new IllegalArgumentException("An application key needs an application id.");
        }
        return APPLICATION_KEY_PREFIX + applicationId;
    }

    /** How a routing key reads in a save-time error message. */
    static String describeRoutingKey(String key) {
        if (key != null && key.startsWith(APPLICATION_KEY_PREFIX)) {
            return "application id '" + key.substring(APPLICATION_KEY_PREFIX.length()) + "'";
        }
        return "channelId '" + key + "'";
    }
}
