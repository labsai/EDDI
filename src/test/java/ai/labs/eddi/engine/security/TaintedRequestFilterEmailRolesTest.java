/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sender-role edit (email plan v3 §9) on the receiving side of the
 * escalation guard: a request that carries the email taint can never change an
 * integration's roles, approved or not, while reading them stays possible.
 */
@DisplayName("TaintedRequestFilter — email sender roles")
class TaintedRequestFilterEmailRolesTest {

    private static final String PATH = "/channelstore/channels/aabbccddee1122334455aabb/email-roles";

    @Test
    @DisplayName("a tainted PUT of the roles is refused, also when a person approved the call")
    void taintedWriteRefused() {
        assertTrue(TaintedRequestFilter.refused("PUT", PATH, List.of(TurnOrigin.EMAIL)));
        assertTrue(TaintedRequestFilter.refusal("PUT", PATH, true, true).isPresent(), "never, whoever approves");
        assertTrue(TaintedRequestFilter.refused("PUT", "/api" + PATH + "/", List.of(TurnOrigin.EMAIL)), "behind a prefix too");
    }

    @Test
    @DisplayName("an untainted write and a tainted read pass")
    void othersPass() {
        assertFalse(TaintedRequestFilter.refused("PUT", PATH, null));
        assertFalse(TaintedRequestFilter.refused("GET", PATH, List.of(TurnOrigin.EMAIL)));
    }
}
