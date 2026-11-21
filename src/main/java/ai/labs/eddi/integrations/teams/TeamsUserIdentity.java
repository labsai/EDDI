/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.teams;

import java.util.Locale;

/**
 * The EDDI user id of a Teams user: {@code teams:<tenantId>:<aadObjectId>},
 * both lower-case GUIDs. The tenant is the one the verified activity carried
 * and that passed the integration's tenant check; the object id is the user's
 * Entra object id, stable across chats and teams (the Teams-scoped
 * {@code from.id} is not used, as it differs per bot).
 * <p>
 * Both parts are validated as GUIDs before they are joined, so no value can
 * contain the {@code :} separator and name another tenant's user.
 * <p>
 * Like a Slack identity this is a channel identity, not an authenticated EDDI
 * login: conversations started with it are not verified principals, so a
 * {@code PER_USER} connection is never resolved for them.
 *
 * @since 6.7.0
 */
public final class TeamsUserIdentity {

    public static final String PREFIX = "teams:";

    private TeamsUserIdentity() {
    }

    /**
     * @throws IllegalArgumentException
     *             when either part is not a GUID
     */
    public static String eddiUserId(String tenantId, String aadObjectId) {
        if (!TeamsChannelType.isGuid(tenantId) || !TeamsChannelType.isGuid(aadObjectId)) {
            throw new IllegalArgumentException("Teams tenant and user ids must be GUIDs");
        }
        return PREFIX + tenantId.toLowerCase(Locale.ROOT) + ":" + aadObjectId.toLowerCase(Locale.ROOT);
    }

    public static boolean isTeamsPrincipal(String userId) {
        return userId != null && userId.startsWith(PREFIX);
    }
}
