/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import java.util.regex.Pattern;

/**
 * How a Slack user is named inside EDDI.
 * <p>
 * EDDI used to take the raw Slack user id ({@code U0123ABCD}) as the
 * conversation's {@code userId}. That id lives in the same namespace as OIDC
 * principals, REST callers and every other channel, so a Keycloak user whose
 * principal happened to equal a Slack id — or who chose it, where usernames are
 * self-service — shared that Slack user's long-term memories, passed ownership
 * checks on their conversations, and could export or erase their data. Slack
 * ids are also only unique per workspace, so two workspaces on one deployment
 * could collide with each other.
 * <p>
 * The EDDI user id is now {@code slack:<team_id>:<user_id>}: prefixed so no
 * other identity source can produce it, and qualified by workspace. (Without a
 * team id — Slack always sends one on {@code event_callback} — it degrades to
 * {@code slack:<user_id>}, still out of the shared namespace.)
 * <p>
 * <b>Compatibility with data stored under the raw id.</b> Handled in
 * {@link SlackEventHandler} and it is <b>adopt-only</b>: a thread whose
 * conversation mapping was stored under the raw id keeps its conversation — the
 * mapping is found by the raw id, re-keyed to the namespaced id, and the
 * conversation continues. That conversation still carries the raw id as its
 * owner (it is not rewritten), so it keeps loading the memories it always did.
 * There is deliberately <b>no</b> standalone move of long-term memory out of
 * the bare Slack-id namespace: that id carries no workspace and the namespace
 * is shared across every source, so a move keyed on the (attacker-supplied in a
 * signed event) team could relocate a victim's memories into another namespace
 * (review Finding B). A brand-new conversation therefore does not inherit
 * memories the raw id accumulated before namespacing.
 */
public final class SlackUserIdentity {

    /** Prefix of every EDDI user id derived from Slack. */
    public static final String PREFIX = "slack:";

    /**
     * Shape of a raw Slack user id: {@code U…} for a workspace user, {@code W…} for
     * an Enterprise Grid user. Anything else stored under a bare id is not a Slack
     * identity and is never migrated.
     */
    private static final Pattern RAW_SLACK_USER_ID = Pattern.compile("^[UW][A-Z0-9]{2,}$");

    private SlackUserIdentity() {
    }

    /**
     * The EDDI user id for a Slack user.
     *
     * @param teamId
     *            the workspace ({@code team_id}); may be {@code null}
     * @param slackUserId
     *            the raw Slack user id
     * @return {@code slack:<team_id>:<user_id>}, or {@code slack:<user_id>} without
     *         a team
     */
    public static String eddiUserId(String teamId, String slackUserId) {
        if (teamId == null || teamId.isBlank()) {
            return PREFIX + slackUserId;
        }
        return PREFIX + teamId + ":" + slackUserId;
    }

    /**
     * Whether {@code userId} looks like a raw, pre-namespacing Slack user id — i.e.
     * data stored under it may be migrated to the namespaced identity.
     */
    public static boolean isLegacySlackUserId(String userId) {
        return userId != null && RAW_SLACK_USER_ID.matcher(userId).matches();
    }
}
