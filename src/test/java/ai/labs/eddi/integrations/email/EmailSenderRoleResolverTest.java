/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.configs.channels.model.EmailControl.RolePolicy;
import ai.labs.eddi.configs.channels.model.EmailControl.SenderRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The runtime role of a mail (email plan v3 §9): only ever a narrowing. */
@DisplayName("EmailSenderRoleResolver")
class EmailSenderRoleResolverTest {

    private static final EmailControl CONTROL = new EmailControl(
            List.of(new SenderRole("exec", List.of("cfo@company.com")), new SenderRole("staff", List.of("*@company.com")),
                    new SenderRole("customer", List.of("*@customer-a.com"))),
            Map.of("exec", new RolePolicy(null, null, null, null, null),
                    "staff", new RolePolicy(null, null, null, null, 30),
                    "customer", new RolePolicy(List.of("support"), List.of("kb_search"), null, null, null)));

    private static ChannelTarget target(String name, ChannelTarget.TargetType type) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setType(type);
        return target;
    }

    @Test
    @DisplayName("without roles nothing changes: role default, no restriction, the integration's cap")
    void noRoles() {
        var resolved = EmailSenderRoleResolver.resolve(null, "x@y.com", true, 50);
        assertEquals("default", resolved.role());
        assertFalse(resolved.restricts());
        assertEquals(50, resolved.maxTurnsPerSenderPerDay());
        // and an unauthenticated mail keeps today's behaviour too
        assertFalse(EmailSenderRoleResolver.resolve(new EmailControl(), "x@y.com", false, 50).restricts());
    }

    @Test
    @DisplayName("first matching entry wins")
    void firstMatchWins() {
        assertEquals("exec", EmailSenderRoleResolver.resolve(CONTROL, "cfo@company.com", true, 50).role());
        assertEquals("staff", EmailSenderRoleResolver.resolve(CONTROL, "bob@company.com", true, 50).role());
        assertEquals("default", EmailSenderRoleResolver.resolve(CONTROL, "eve@else.com", true, 50).role());
    }

    @Test
    @DisplayName("a forged From (no aligned pass) never gets the role it names")
    void forgedFromIsAnonymous() {
        var resolved = EmailSenderRoleResolver.resolve(CONTROL, "cfo@company.com", false, 50);
        assertEquals("anonymous", resolved.role());
        assertEquals(Set.of(), resolved.toolCeiling(), "an unauthenticated mail gets no tools at all");
        assertFalse(resolved.allowsTarget(target("panel", ChannelTarget.TargetType.GROUP)));
    }

    @Test
    @DisplayName("a customer reaches only its targets, with only its tools")
    void customerNarrowed() {
        var resolved = EmailSenderRoleResolver.resolve(CONTROL, "ann@customer-a.com", true, 50);
        assertTrue(resolved.allowsTarget(target("Support", ChannelTarget.TargetType.AGENT)));
        assertFalse(resolved.allowsTarget(target("finance", ChannelTarget.TargetType.AGENT)));
        assertEquals(Set.of("kb_search"), resolved.toolCeiling());
    }

    @Test
    @DisplayName("a stored cap above the integration's still yields the integration's: the lower one wins")
    void capNeverWidens() {
        var widening = new EmailControl(List.of(new SenderRole("staff", List.of("*@company.com"))),
                Map.of("staff", new RolePolicy(null, null, null, null, 500)));
        assertEquals(50, EmailSenderRoleResolver.resolve(widening, "bob@company.com", true, 50).maxTurnsPerSenderPerDay());
        assertEquals(30, EmailSenderRoleResolver.resolve(CONTROL, "bob@company.com", true, 50).maxTurnsPerSenderPerDay());
        assertEquals(30, EmailSenderRoleResolver.resolve(CONTROL, "bob@company.com", true, 0).maxTurnsPerSenderPerDay());
        assertEquals(EmailSenderRoleResolver.cap(50, 0), 50);
    }

    @Test
    @DisplayName("a role that slipped in without a policy fails closed")
    void roleWithoutPolicyRefused() {
        var broken = new EmailControl(List.of(new SenderRole("ghost", List.of("*@company.com"))), Map.of());
        var resolved = EmailSenderRoleResolver.resolve(broken, "bob@company.com", true, 50);
        assertNotNull(resolved.refusal());
    }

    @Test
    @DisplayName("policies without any role still apply: a lone anonymous policy restricts unauthenticated mail")
    void policiesAloneActivate() {
        var anonymousOnly = new EmailControl(null, Map.of("anonymous", new RolePolicy(List.of("support"), null, null, null, null)));
        var unauthenticated = EmailSenderRoleResolver.resolve(anonymousOnly, "x@y.com", false, 50);
        assertEquals("anonymous", unauthenticated.role());
        assertEquals(Set.of(), unauthenticated.toolCeiling());
        assertFalse(unauthenticated.allowsTarget(target("finance", ChannelTarget.TargetType.AGENT)));

        var defaultOnly = new EmailControl(List.of(), Map.of("default", new RolePolicy(null, List.of("kb_search"), null, null, 5)));
        var authenticated = EmailSenderRoleResolver.resolve(defaultOnly, "x@y.com", true, 50);
        assertEquals("default", authenticated.role());
        assertEquals(Set.of("kb_search"), authenticated.toolCeiling());
        assertEquals(5, authenticated.maxTurnsPerSenderPerDay());
    }

    @Test
    @DisplayName("an unrestricted role still has no tool ceiling")
    void execUnrestricted() {
        var resolved = EmailSenderRoleResolver.resolve(CONTROL, "cfo@company.com", true, 50);
        assertNull(resolved.toolCeiling());
        assertTrue(resolved.allowsTarget(target("panel", ChannelTarget.TargetType.GROUP)));
    }
}
