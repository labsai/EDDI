/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.ChannelTarget.ApprovalPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The save warning {@code email.read-only-everything} (email plan v3 §8.1): a
 * gated target whose {@code readOnlyTools} hold a pattern that matches every
 * tool has turned the gate off for tools.
 */
class EmailReadOnlyEverythingWarningTest {

    private static ChannelTarget target(String name, ApprovalPolicy policy, String... readOnly) {
        ChannelTarget target = new ChannelTarget();
        target.setName(name);
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId("aaaaaaaaaaaaaaaaaaaaaaaa");
        target.setApprovalPolicy(policy);
        target.setReadOnlyTools(List.of(readOnly));
        return target;
    }

    @Test
    @DisplayName("* (or a pattern as wide) on a gated target warns; named tools and an ungated target do not")
    void warnsOnEverything() {
        var targets = List.of(target("star", ApprovalPolicy.ACTIONS, "lookup", "*"), target("stars", ApprovalPolicy.EVERY_TURN, " ** "),
                target("named", ApprovalPolicy.ACTIONS, "http.get:*", "mcp:search", "lookup_*"),
                target("ungated", ApprovalPolicy.NONE, "*"));

        assertEquals(List.of("star", "stars"), EmailChannelSupport.targetsWithEverythingReadOnly(targets));
        assertTrue(EmailChannelSupport.targetsWithEverythingReadOnly(null).isEmpty());
    }
}
