/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Warns at startup when Slack user ids are NOT namespaced by team although the
 * deployment serves more than one Slack app.
 * <p>
 * Slack user ids are unique within a workspace only. With bare ids (the
 * default, {@code eddi.slack.namespace-user-ids=false}) and several apps —
 * typically several workspaces — two different people can share an EDDI user
 * id, and with it conversations and long-term memory. Namespacing is opt-in
 * because turning it on changes every existing Slack user's id; this check
 * makes the trade-off visible where it matters instead of leaving it to be
 * found in a data incident. Informational only: it never blocks startup.
 */
@ApplicationScoped
public class SlackIdentityStartupCheck {

    private static final Logger LOGGER = Logger.getLogger(SlackIdentityStartupCheck.class);

    private final ChannelTargetRouter channelTargetRouter;
    private final SlackConfig slackConfig;

    @Inject
    public SlackIdentityStartupCheck(ChannelTargetRouter channelTargetRouter, SlackConfig slackConfig) {
        this.channelTargetRouter = channelTargetRouter;
        this.slackConfig = slackConfig;
    }

    void onStart(@Observes StartupEvent event) {
        check();
    }

    /**
     * @return {@code true} when the warning was logged
     */
    boolean check() {
        if (slackConfig.isNamespaceUserIds()) {
            return false;
        }
        try {
            int apps = channelTargetRouter.getSigningSecrets("slack").size();
            if (apps > 1) {
                LOGGER.warnf("%d Slack apps are configured but Slack user ids are not namespaced by workspace "
                        + "(eddi.slack.namespace-user-ids=false). Slack ids are unique per workspace only, so users of "
                        + "different workspaces can share an EDDI user id — and its conversations and long-term memory. "
                        + "Set eddi.slack.namespace-user-ids=true (see docs/slack-integration.md for the migration).", apps);
                return true;
            }
        } catch (RuntimeException e) {
            LOGGER.debugf("Slack identity startup check skipped: %s", e.getMessage());
        }
        return false;
    }
}
