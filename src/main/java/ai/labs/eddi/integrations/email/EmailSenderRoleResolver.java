/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.channels.model.EmailControl;
import ai.labs.eddi.configs.channels.model.EmailControl.RolePolicy;
import ai.labs.eddi.configs.channels.model.EmailControl.SenderRole;
import ai.labs.eddi.configs.channels.model.EmailSenderRoles;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Which role a mail's sender has, and what that role leaves of the
 * integration's limits (email plan v3 §9). Pure: called once per mail by
 * {@link EmailInboundProcessor}, after authentication, the sender lists and
 * replay protection, with the sender of the <b>signed view</b>.
 * <p>
 * Everything is computed as a narrowing of what already applies, so a stored
 * policy that widens (a cap above the integration's, a target it does not have)
 * widens nothing:
 * <ul>
 * <li>an unauthenticated mail is {@link EmailSenderRoles#ROLE_ANONYMOUS} before
 * any pattern is looked at — a bare {@code From} never earns a role;</li>
 * <li>a target must pass the target's own rules ({@code allowedSenders}) and
 * the role's {@code targets};</li>
 * <li>the per-sender cap is the lower of the integration's and the role's;</li>
 * <li>the tool ceiling only removes tools.</li>
 * </ul>
 */
public final class EmailSenderRoleResolver {

    private EmailSenderRoleResolver() {
    }

    /**
     * The role a mail runs under.
     *
     * @param role
     *            the role's name, for {@code mail.senderRole}; prompts only, never
     *            read back for enforcement
     * @param targets
     *            target names (lower case) the role may reach; {@code null} for
     *            every target
     * @param toolCeiling
     *            the only tool names a turn may call; {@code null} for no
     *            restriction, empty for none at all
     * @param maxTurnsPerSenderPerDay
     *            the effective cap, {@code 0} for none
     * @param refusal
     *            non-null when the mail must be refused outright (a role without a
     *            policy: fail closed)
     */
    public record Resolved(String role, Set<String> targets, Set<String> toolCeiling, int maxTurnsPerSenderPerDay, String refusal) {

        /** Whether the role lets the mail reach {@code target}. */
        public boolean allowsTarget(ChannelTarget target) {
            if (target == null) {
                return false;
            }
            // A group runs its members' own tools; a tool ceiling cannot follow it there.
            if (toolCeiling != null && target.getType() == ChannelTarget.TargetType.GROUP) {
                return false;
            }
            return targets == null || (target.getName() != null && targets.contains(target.getName().trim().toLowerCase(Locale.ROOT)));
        }

        /** Whether roles restrict anything for this mail. */
        public boolean restricts() {
            return targets != null || toolCeiling != null;
        }
    }

    /**
     * Resolves the role of a mail.
     *
     * @param control
     *            the integration's {@code emailControl}, or {@code null}
     * @param sender
     *            the normalised sender of the signed view
     * @param authenticated
     *            whether the mail has an aligned DKIM pass
     * @param integrationCap
     *            the integration's {@code maxTurnsPerSenderPerDay}, {@code 0} for
     *            none
     */
    public static Resolved resolve(EmailControl control, String sender, boolean authenticated, int integrationCap) {
        if (control == null || !control.isActive()) {
            // No roles configured: today's behaviour, exactly.
            return new Resolved(EmailSenderRoles.ROLE_DEFAULT, null, null, integrationCap, null);
        }
        Map<String, RolePolicy> policies = control.getRolePolicies() == null ? Map.of() : control.getRolePolicies();
        if (!authenticated) {
            RolePolicy policy = policies.get(EmailSenderRoles.ROLE_ANONYMOUS);
            // An unauthenticated sender never gets a tool, whatever is stored.
            return narrowed(EmailSenderRoles.ROLE_ANONYMOUS, policy, Set.of(), integrationCap);
        }
        String role = EmailSenderRoles.ROLE_DEFAULT;
        for (SenderRole entry : control.hasRoles() ? control.getSenderRoles() : List.<SenderRole>of()) {
            if (entry != null && entry.role() != null && entry.senders() != null && sender != null
                    && SenderPatterns.matchesAny(sender, entry.senders())) {
                role = entry.role();
                break;
            }
        }
        RolePolicy policy = policies.get(role);
        if (policy == null && !EmailSenderRoles.RESERVED_ROLES.contains(role)) {
            return new Resolved(role, Set.of(), Set.of(), integrationCap, "blocked:role_without_policy");
        }
        return narrowed(role, policy, policy == null ? null : ceiling(policy.tools()), integrationCap);
    }

    private static Resolved narrowed(String role, RolePolicy policy, Set<String> toolCeiling, int integrationCap) {
        Set<String> targets = policy == null ? null : targets(policy.targets());
        int cap = cap(integrationCap, policy == null ? null : policy.maxTurnsPerSenderPerDay());
        return new Resolved(role, targets, toolCeiling, cap, null);
    }

    /** The lower of two caps, where {@code 0} (or absent) means none. */
    static int cap(int integrationCap, Integer roleCap) {
        if (roleCap == null || roleCap <= 0) {
            return integrationCap;
        }
        return integrationCap <= 0 ? roleCap : Math.min(integrationCap, roleCap);
    }

    private static Set<String> targets(List<String> names) {
        if (names == null || names.contains(EmailSenderRoles.ALL_TARGETS)) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String name : names) {
            if (name != null) {
                out.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }

    private static Set<String> ceiling(List<String> tools) {
        if (tools == null) {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        for (String tool : tools) {
            if (tool != null && !tool.isBlank()) {
                out.add(tool.trim());
            }
        }
        return Set.copyOf(out);
    }
}
