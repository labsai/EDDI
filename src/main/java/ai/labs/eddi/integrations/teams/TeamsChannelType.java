/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.integrations.channels.ChannelTypeDescriptor;
import ai.labs.eddi.integrations.channels.ConnectionIdentityRequirement;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Microsoft Teams, through an Azure Bot. Connection-backed: the integration
 * holds ids only, and the bot's credential (a client secret or, preferably, a
 * certificate with {@code clientAuthMethod=private_key_jwt}) lives in the EDDI
 * connection named by {@code platformConfig.connection}.
 * <p>
 * Keys:
 * <ul>
 * <li>{@code appId} (required): the bot's Microsoft App ID, the {@code aud} of
 * every inbound Bot Connector token.</li>
 * <li>{@code tenantId} (required): the Entra tenant of the single-tenant app
 * registration. Its token endpoint is pinned from it.</li>
 * <li>{@code allowedTenantIds}: further tenants whose activities are accepted,
 * comma-separated. Activities from {@code tenantId} are always accepted; an
 * empty list means that tenant only, never "every tenant".</li>
 * <li>{@code conversationId}: a Teams channel or group chat
 * ({@code 19:…@thread…}) this integration answers in. Without one the
 * integration serves the app's personal chats and every conversation no other
 * integration claims, and is routed by its application key.</li>
 * <li>{@code hitlApprovalConversationId}, {@code hitlApproverAadObjectIds}:
 * reserved for approval cards, validated but not used yet.</li>
 * </ul>
 * <p>
 * Who may declare an {@code appId}: the connection's {@code oauth.clientId}
 * must be that app id, its token endpoint must be the tenant's Entra endpoint,
 * and at save time it must obtain a token there. Only someone holding the app's
 * secret or certificate can therefore claim the app's traffic.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class TeamsChannelType implements ChannelTypeDescriptor {

    public static final String ID = "teams";

    public static final String KEY_APP_ID = "appId";
    public static final String KEY_TENANT_ID = "tenantId";
    public static final String KEY_ALLOWED_TENANT_IDS = "allowedTenantIds";
    public static final String KEY_CONVERSATION_ID = "conversationId";
    public static final String KEY_HITL_CONVERSATION_ID = "hitlApprovalConversationId";
    public static final String KEY_HITL_APPROVERS = "hitlApproverAadObjectIds";

    /** The scope of a Bot Connector token. */
    public static final String BOT_CONNECTOR_SCOPE = "https://api.botframework.com/.default";

    private static final Set<String> ALLOWED_KEYS = Set.of(KEY_APP_ID, KEY_TENANT_ID, KEY_CONNECTION, KEY_ALLOWED_TENANT_IDS,
            KEY_CONVERSATION_ID, KEY_HITL_CONVERSATION_ID, KEY_HITL_APPROVERS);

    private static final Pattern GUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    /**
     * A channel or group-chat conversation id, without the {@code ;messageid=}
     * suffix of a channel thread.
     */
    private static final Pattern CONVERSATION_ID = Pattern.compile("^19:[A-Za-z0-9._@\\-]{1,250}$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean connectionBacked() {
        return true;
    }

    @Override
    public Optional<Set<String>> allowedPlatformConfigKeys() {
        return Optional.of(ALLOWED_KEYS);
    }

    /**
     * Client credentials only, with a secret or, preferably, a certificate
     * ({@code clientAuthMethod=private_key_jwt}). {@code OAUTH2_JWT_BEARER} is the
     * RFC 7523 §2.1 grant (Google service accounts), for which Entra issues no app
     * token.
     */
    @Override
    public Set<AuthType> acceptedConnectionAuthTypes() {
        return Set.of(AuthType.OAUTH2_CLIENT_CREDENTIALS);
    }

    @Override
    public boolean supportsObserveMode() {
        return false;
    }

    @Override
    public Optional<String> applicationId(Map<String, String> platformConfig) {
        String appId = platformConfig == null ? null : platformConfig.get(KEY_APP_ID);
        return isGuid(appId) ? Optional.of(appId.toLowerCase(Locale.ROOT)) : Optional.empty();
    }

    /** {@code conversationId} when set, otherwise the application key. */
    @Override
    public List<String> routingKeys(String resourceId, Map<String, String> platformConfig) {
        String conversationId = platformConfig == null ? null : platformConfig.get(KEY_CONVERSATION_ID);
        if (conversationId != null && !conversationId.isBlank()) {
            return List.of(conversationId);
        }
        return applicationId(platformConfig).map(id -> List.of(ChannelTypeDescriptor.applicationKey(id))).orElse(List.of());
    }

    @Override
    public Optional<ConnectionIdentityRequirement> requiredConnectionIdentity(Map<String, String> platformConfig) {
        Optional<String> appId = applicationId(platformConfig);
        String tenantId = platformConfig == null ? null : platformConfig.get(KEY_TENANT_ID);
        if (appId.isEmpty() || !isGuid(tenantId)) {
            return Optional.empty();
        }
        return Optional.of(new ConnectionIdentityRequirement(appId.get(), tokenUrl(tenantId), BOT_CONNECTOR_SCOPE));
    }

    /** The Entra token endpoint of a single-tenant app registration. */
    public static String tokenUrl(String tenantId) {
        return "https://login.microsoftonline.com/" + tenantId.toLowerCase(Locale.ROOT) + "/oauth2/v2.0/token";
    }

    @Override
    public List<String> validate(Map<String, String> platformConfig) {
        Map<String, String> pc = platformConfig == null ? Map.of() : platformConfig;
        List<String> errors = new ArrayList<>();
        if (!isGuid(pc.get(KEY_APP_ID))) {
            errors.add("platformConfig.appId is required: the bot's Microsoft App ID (a GUID).");
        }
        if (!isGuid(pc.get(KEY_TENANT_ID))) {
            errors.add("platformConfig.tenantId is required: the Entra tenant id (a GUID) of the bot's single-tenant app registration.");
        }
        if (!guidList(pc.get(KEY_ALLOWED_TENANT_IDS)).valid()) {
            errors.add("platformConfig.allowedTenantIds must be a comma-separated list of tenant ids (GUIDs).");
        }
        if (!guidList(pc.get(KEY_HITL_APPROVERS)).valid()) {
            errors.add("platformConfig.hitlApproverAadObjectIds must be a comma-separated list of Entra object ids (GUIDs).");
        }
        for (String key : List.of(KEY_CONVERSATION_ID, KEY_HITL_CONVERSATION_ID)) {
            String value = pc.get(key);
            if (value != null && !value.isBlank() && !CONVERSATION_ID.matcher(value).matches()) {
                errors.add("platformConfig." + key + " must be a Teams channel or group chat id (19:…), without ';messageid='.");
            }
        }
        return errors;
    }

    /**
     * The tenants whose activities an integration accepts: its own {@code tenantId}
     * plus {@code allowedTenantIds}, lower-case. Empty when the configuration is
     * invalid, so nothing is accepted.
     */
    public static Set<String> acceptedTenants(Map<String, String> platformConfig) {
        Map<String, String> pc = platformConfig == null ? Map.of() : platformConfig;
        String own = pc.get(KEY_TENANT_ID);
        GuidList extra = guidList(pc.get(KEY_ALLOWED_TENANT_IDS));
        if (!isGuid(own) || !extra.valid()) {
            return Set.of();
        }
        Set<String> tenants = new LinkedHashSet<>();
        tenants.add(own.toLowerCase(Locale.ROOT));
        tenants.addAll(extra.values());
        return tenants;
    }

    public static boolean isGuid(String value) {
        return value != null && GUID.matcher(value).matches();
    }

    record GuidList(boolean valid, List<String> values) {
    }

    static GuidList guidList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new GuidList(true, List.of());
        }
        List<String> values = new ArrayList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!isGuid(trimmed)) {
                return new GuidList(false, List.of());
            }
            values.add(trimmed.toLowerCase(Locale.ROOT));
        }
        return new GuidList(true, values);
    }
}
