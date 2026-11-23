/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import ai.labs.eddi.configs.channels.model.EmailControl.RolePolicy;
import ai.labs.eddi.configs.channels.model.EmailControl.SenderRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Save-time validation of sender roles (email plan v3 §9.1): a role only
 * narrows.
 */
@DisplayName("EmailSenderRoles — validation")
class EmailSenderRolesTest {

    private static ChannelTarget target(String name, ChannelTarget.TargetType type) {
        var target = new ChannelTarget();
        target.setName(name);
        target.setType(type);
        target.setTargetId(name + "-id");
        return target;
    }

    private static final List<ChannelTarget> TARGETS = List.of(target("support", ChannelTarget.TargetType.AGENT),
            target("finance", ChannelTarget.TargetType.AGENT), target("panel", ChannelTarget.TargetType.GROUP));

    private static RolePolicy policy(List<String> targets, List<String> tools, Integer cap) {
        return new RolePolicy(targets, tools, null, null, cap);
    }

    private static EmailControl control(List<SenderRole> roles, Map<String, RolePolicy> policies) {
        return new EmailControl(roles, policies);
    }

    private static String refusal(EmailControl control) {
        return assertThrows(IllegalArgumentException.class, () -> EmailSenderRoles.validate(control, TARGETS, 50)).getMessage();
    }

    @Test
    @DisplayName("a valid control is normalised: patterns lower-cased, targets as the integration spells them")
    void normalises() {
        var normalised = EmailSenderRoles.validate(control(List.of(new SenderRole("exec", List.of("CFO@Company.com", "*@Company.com"))),
                Map.of("exec", policy(List.of("FINANCE"), List.of("kb_search"), 10))), TARGETS, 50);
        assertEquals(List.of("cfo@company.com", "*@company.com"), normalised.getSenderRoles().getFirst().senders());
        assertEquals(List.of("finance"), normalised.getRolePolicies().get("exec").targets());
    }

    @Test
    @DisplayName("an empty control is stored as none")
    void emptyIsNull() {
        assertNull(EmailSenderRoles.validate(new EmailControl(), TARGETS, 50));
    }

    @Test
    @DisplayName("a role's cap above the integration's is refused; 0 inherits")
    void capCannotWiden() {
        assertTrue(refusal(control(List.of(new SenderRole("staff", List.of("*@company.com"))), Map.of("staff", policy(null, null, 51))))
                .contains("can only lower it"));
        var inherited = EmailSenderRoles.validate(
                control(List.of(new SenderRole("staff", List.of("*@company.com"))), Map.of("staff", policy(null, null, 0))), TARGETS, 50);
        assertNull(inherited.getRolePolicies().get("staff").maxTurnsPerSenderPerDay());
    }

    @Test
    @DisplayName("a target the integration does not have is refused")
    void unknownTarget() {
        assertTrue(refusal(control(List.of(new SenderRole("customer", List.of("*@a.com"))), Map.of("customer", policy(List.of("hr"), null, null))))
                .contains("'hr' is not a target"));
    }

    @Test
    @DisplayName("a role without a policy is refused — fail closed")
    void roleNeedsPolicy() {
        assertTrue(refusal(control(List.of(new SenderRole("customer", List.of("*@a.com"))), Map.of())).contains("has no policy"));
    }

    @Test
    @DisplayName("the built-in role names cannot be given to senders")
    void reservedNames() {
        var policies = new LinkedHashMap<String, RolePolicy>();
        policies.put("default", policy(null, null, null));
        assertTrue(refusal(control(List.of(new SenderRole("default", List.of("*@a.com"))), policies)).contains("built-in role"));
    }

    @Test
    @DisplayName("an unauthenticated sender never gets tools")
    void anonymousNoTools() {
        assertTrue(refusal(control(List.of(new SenderRole("staff", List.of("*@company.com"))),
                Map.of("staff", policy(null, null, null), "anonymous", policy(null, List.of("kb_search"), null)))).contains("never gets tools"));
    }

    @Test
    @DisplayName("settings of phases that have not shipped are refused, not silently ignored")
    void unshippedSettings() {
        var roles = List.of(new SenderRole("exec", List.of("cfo@company.com")));
        assertTrue(refusal(control(roles, Map.of("exec", new RolePolicy(null, null, "actions", null, null)))).contains("approval gate"));
        assertTrue(refusal(control(roles, Map.of("exec", new RolePolicy(null, null, null, true, null)))).contains("send-out"));
        // the values that change nothing are accepted
        EmailSenderRoles.validate(control(roles, Map.of("exec", new RolePolicy(null, null, "none", false, null))), TARGETS, 50);
    }

    @Test
    @DisplayName("a role restricting tools cannot name a group target")
    void restrictedRoleNoGroup() {
        assertTrue(refusal(control(List.of(new SenderRole("customer", List.of("*@a.com"))),
                Map.of("customer", policy(List.of("panel"), List.of("kb_search"), null)))).contains("is a group"));
    }

    @Test
    @DisplayName("a role with tools reaching a group through \"*\" or no targets is refused too")
    void restrictedRoleNoGroupByWildcard() {
        var roles = List.of(new SenderRole("customer", List.of("*@a.com")));
        assertTrue(refusal(control(roles, Map.of("customer", policy(null, List.of("kb_search"), null)))).contains("is a group"));
        assertTrue(refusal(control(roles, Map.of("customer", policy(List.of("*"), List.of("kb_search"), null)))).contains("is a group"));
        // without tools a role may reach a group
        EmailSenderRoles.validate(control(roles, Map.of("customer", policy(null, null, null))), TARGETS, 50);
    }

    @Test
    @DisplayName("the audit diff names the changed fields of a policy, never an address")
    void diffFields() {
        var roles = List.of(new SenderRole("a", List.of("a@x.com")));
        var changes = EmailSenderRoles.diff(control(roles, Map.of("a", policy(List.of("support"), null, 5))),
                control(roles, Map.of("a", policy(List.of("finance"), List.of("kb"), 5))));
        assertEquals(List.of("policy~:a[targets,tools]"), changes);
    }

    @Test
    @DisplayName("invalid patterns, duplicate roles and unknown policy keys are refused")
    void malformed() {
        assertTrue(refusal(control(List.of(new SenderRole("x", List.of("not-an-address"))), Map.of("x", policy(null, null, null))))
                .contains("must be 'user@domain'"));
        assertTrue(refusal(control(List.of(new SenderRole("x", List.of("a@b.com")), new SenderRole("x", List.of("c@d.com"))),
                Map.of("x", policy(null, null, null)))).contains("listed twice"));
        assertTrue(refusal(control(List.of(new SenderRole("x", List.of("a@b.com"))), Map.of("x", policy(null, null, null), "y",
                policy(null, null, null)))).contains("'y' is not a role"));
        assertTrue(refusal(control(List.of(new SenderRole("Bad Name", List.of("a@b.com"))), Map.of())).contains("lower-case"));
    }

    @Test
    @DisplayName("the audit diff names roles and policies added, removed and changed")
    void diff() {
        var before = control(List.of(new SenderRole("a", List.of("a@x.com")), new SenderRole("b", List.of("b@x.com"))),
                Map.of("a", policy(null, null, null), "b", policy(null, null, null)));
        var after = control(List.of(new SenderRole("a", List.of("a@x.com", "a2@x.com")), new SenderRole("c", List.of("c@x.com"))),
                Map.of("a", policy(null, List.of("kb"), null), "c", policy(null, null, null)));
        List<String> changes = EmailSenderRoles.diff(before, after);
        assertTrue(changes.containsAll(List.of("role~:a", "role+:c", "role-:b", "policy~:a[tools]", "policy+:c", "policy-:b")), changes.toString());
    }
}
