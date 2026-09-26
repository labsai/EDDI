/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.integrations.channels.ChannelTargetRouter;

import java.util.HashMap;
import java.util.Map;

/**
 * What the Events API envelope says about who sent an event, as opposed to what
 * the event itself asks for.
 * <p>
 * The event names a channel and a user; nothing about those is authenticated
 * beyond "some configured Slack app signed this body". The envelope is where
 * the sender is pinned down: the signing secret that verified the request
 * identifies the app, and {@code team_id} / {@code api_app_id} identify the
 * workspace and the app as Slack reports them. Every routing decision checks
 * the integration it lands on against this, so an event can only ever act on
 * integrations that belong to the app that sent it.
 *
 * @param verifiedSigningSecret
 *            the signing secret that verified the request — never logged, never
 *            serialized; compared in constant time
 * @param teamId
 *            the envelope's {@code team_id}, or {@code null}
 * @param apiAppId
 *            the envelope's {@code api_app_id}, or {@code null}
 * @param botUserId
 *            this app's own Slack user id from {@code authorizations[]}, or
 *            {@code null} — see {@code SlackEventHandler.mentionsThisBot}
 */
public record SlackEventEnvelope(String verifiedSigningSecret, String teamId, String apiAppId, String botUserId) {

    /**
     * The envelope's platform identifiers, keyed the way an integration pins them
     * ({@link ChannelTargetRouter#CFG_TEAM_ID},
     * {@link ChannelTargetRouter#CFG_APP_ID}).
     */
    public Map<String, String> inboundIds() {
        var ids = new HashMap<String, String>();
        ids.put(ChannelTargetRouter.CFG_TEAM_ID, blankToNull(teamId));
        ids.put(ChannelTargetRouter.CFG_APP_ID, blankToNull(apiAppId));
        return ids;
    }

    /** Never prints the secret. */
    @Override
    public String toString() {
        return "SlackEventEnvelope[teamId=" + teamId + ", apiAppId=" + apiAppId + ", botUserId=" + botUserId + "]";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
