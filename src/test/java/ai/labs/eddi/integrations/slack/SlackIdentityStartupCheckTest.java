/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Bare Slack user ids (the default) collide across workspaces; the startup
 * check warns exactly when that risk exists.
 */
class SlackIdentityStartupCheckTest {

    private static SlackConfig config(boolean namespace) {
        return new SlackConfig(60, 300, 3, 500L, namespace);
    }

    @Test
    void warnsWhenSeveralAppsShareBareIds() {
        var router = mock(ChannelTargetRouter.class);
        when(router.getSigningSecrets("slack")).thenReturn(Set.of("sig-a", "sig-b"));

        assertTrue(new SlackIdentityStartupCheck(router, config(false)).check());
    }

    @Test
    void silentForOneAppOrWhenNamespaced() {
        var router = mock(ChannelTargetRouter.class);
        when(router.getSigningSecrets("slack")).thenReturn(Set.of("sig-a"));
        assertFalse(new SlackIdentityStartupCheck(router, config(false)).check());

        when(router.getSigningSecrets("slack")).thenReturn(Set.of("sig-a", "sig-b"));
        assertFalse(new SlackIdentityStartupCheck(router, config(true)).check());
    }

    @Test
    void defaultIsBareIds() {
        // #2: namespacing is opt-in — turning it on changes every existing user's id.
        assertFalse(new SlackConfig(60, 300, 3, 500L).isNamespaceUserIds());
    }
}
