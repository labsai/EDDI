/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import ai.labs.eddi.configs.channels.model.EmailControl.RolePolicy;
import ai.labs.eddi.configs.channels.model.EmailControl.SenderRole;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Sender roles of an email integration (email plan v3 §9): their validation,
 * and the targeted edit {@code GET}/{@code PUT .../email-roles} with the MCP
 * tools {@code list_email_roles} / {@code update_email_roles}.
 * <p>
 * <b>A role only narrows.</b> {@link #validate} refuses a policy that would
 * widen what the integration allows (a cap above the integration's, a target it
 * does not have, a setting of a phase that has not shipped), and the runtime
 * ({@code EmailSenderRoleResolver}) computes every limit as an intersection
 * again, so a stored document that slipped past this check widens nothing
 * either.
 * <p>
 * Roles are never granted by anything the sender controls: they bind to the
 * authenticated {@code From} of an aligned DKIM pass only, read from the signed
 * view after replay protection; an unauthenticated mail is always
 * {@link #ROLE_ANONYMOUS}.
 */
public final class EmailSenderRoles {

    /** The role of an authenticated sender no {@code senderRoles} entry names. */
    public static final String ROLE_DEFAULT = "default";
    /** The role of every mail without an aligned DKIM pass. */
    public static final String ROLE_ANONYMOUS = "anonymous";
    /** Names a role may not take: the two built-in roles. */
    public static final Set<String> RESERVED_ROLES = Set.of(ROLE_DEFAULT, ROLE_ANONYMOUS);

    /** {@code targets} entry meaning every target of the integration. */
    public static final String ALL_TARGETS = "*";
    /** The only {@code approvalPolicy} accepted until the approval gate (C4). */
    public static final String APPROVAL_NONE = "none";

    public static final int MAX_ROLES = 20;
    public static final int MAX_SENDERS_PER_ROLE = EmailChannelSettings.MAX_LIST_ENTRIES;
    public static final int MAX_TOOLS_PER_POLICY = 100;
    public static final int MAX_TOOL_NAME_LENGTH = 128;

    private static final Pattern ROLE_NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,39}");

    private EmailSenderRoles() {
    }

    /** The roles of one version of an integration. */
    public record View(Integer version, List<SenderRole> senderRoles, Map<String, RolePolicy> rolePolicies) {
    }

    /**
     * The body of {@code PUT .../email-roles}: replaces both the ordered role list
     * and the policies (first match wins, so an edit is a whole list, not a set of
     * additions).
     */
    public record UpdateRequest(Integer expectedVersion, List<SenderRole> senderRoles, Map<String, RolePolicy> rolePolicies,
            String reason) {
    }

    /** The answer to an edit. */
    public record UpdateResult(Integer previousVersion, Integer version, boolean changed, List<SenderRole> senderRoles,
            Map<String, RolePolicy> rolePolicies, List<String> changes, List<String> warnings) {
    }

    /**
     * Checks and normalises {@code control} against the integration it belongs to.
     * Returns the normalised control (patterns lower-cased, target names as written
     * in the integration), or {@code null} when it holds nothing.
     *
     * @param integrationCap
     *            the integration's {@code maxTurnsPerSenderPerDay}; {@code 0} means
     *            no cap
     * @throws IllegalArgumentException
     *             with every problem, each fit for a {@code 400}
     */
    public static EmailControl validate(EmailControl control, List<ChannelTarget> targets, int integrationCap) {
        if (control == null) {
            return null;
        }
        List<String> errors = new ArrayList<>();
        Map<String, ChannelTarget> targetsByName = new LinkedHashMap<>();
        if (targets != null) {
            for (ChannelTarget target : targets) {
                if (target != null && target.getName() != null) {
                    targetsByName.putIfAbsent(target.getName().trim().toLowerCase(Locale.ROOT), target);
                }
            }
        }

        List<SenderRole> roles = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        List<SenderRole> rawRoles = control.getSenderRoles() == null ? List.of() : control.getSenderRoles();
        if (rawRoles.size() > MAX_ROLES) {
            errors.add("emailControl.senderRoles: at most " + MAX_ROLES + " roles.");
        }
        for (SenderRole entry : rawRoles) {
            if (entry == null) {
                errors.add("emailControl.senderRoles: an entry is empty.");
                continue;
            }
            String role = entry.role() == null ? "" : entry.role().trim();
            if (!ROLE_NAME.matcher(role).matches()) {
                errors.add("emailControl.senderRoles: role '" + clip(entry.role()) + "' must be 1-40 lower-case letters, digits, '_' "
                        + "or '-', starting with a letter or digit.");
                continue;
            }
            if (RESERVED_ROLES.contains(role)) {
                errors.add("emailControl.senderRoles: '" + role + "' is a built-in role and cannot be given to senders; configure it in "
                        + "rolePolicies instead.");
                continue;
            }
            if (!declared.add(role)) {
                errors.add("emailControl.senderRoles: role '" + role + "' is listed twice; give it one entry with all its senders.");
                continue;
            }
            List<String> senders = new ArrayList<>();
            List<String> rawSenders = entry.senders() == null ? List.of() : entry.senders();
            if (rawSenders.isEmpty()) {
                errors.add("emailControl.senderRoles: role '" + role + "' names no senders.");
            }
            if (rawSenders.size() > MAX_SENDERS_PER_ROLE) {
                errors.add("emailControl.senderRoles: role '" + role + "' has more than " + MAX_SENDERS_PER_ROLE + " senders.");
            }
            for (String sender : rawSenders) {
                try {
                    String normalised = EmailSenderLists.normalise(sender);
                    if (!senders.contains(normalised)) {
                        senders.add(normalised);
                    }
                } catch (IllegalArgumentException e) {
                    errors.add("emailControl.senderRoles: role '" + role + "': '" + clip(sender) + "' must be 'user@domain', '*@domain' "
                            + "or '*@*.domain'.");
                }
            }
            roles.add(new SenderRole(role, List.copyOf(senders)));
        }

        Map<String, RolePolicy> policies = new LinkedHashMap<>();
        Map<String, RolePolicy> rawPolicies = control.getRolePolicies() == null ? Map.of() : control.getRolePolicies();
        for (Map.Entry<String, RolePolicy> entry : rawPolicies.entrySet()) {
            String role = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!declared.contains(role) && !RESERVED_ROLES.contains(role)) {
                errors.add("emailControl.rolePolicies: '" + clip(entry.getKey()) + "' is not a role in senderRoles (nor 'default' or "
                        + "'anonymous').");
                continue;
            }
            if (entry.getValue() == null) {
                errors.add("emailControl.rolePolicies." + role + " is empty.");
                continue;
            }
            policies.put(role, validatePolicy(role, entry.getValue(), targetsByName, integrationCap, errors));
        }
        for (String role : declared) {
            if (!policies.containsKey(role) && !rawPolicies.containsKey(role)) {
                errors.add("emailControl.rolePolicies: role '" + role + "' has no policy. Every role needs one, even if it is {}.");
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", errors));
        }
        if (roles.isEmpty() && policies.isEmpty()) {
            return null;
        }
        return new EmailControl(roles.isEmpty() ? null : roles, policies.isEmpty() ? null : policies);
    }

    private static RolePolicy validatePolicy(String role, RolePolicy policy, Map<String, ChannelTarget> targetsByName, int integrationCap,
                                             List<String> errors) {
        String where = "emailControl.rolePolicies." + role;
        List<String> targets = null;
        boolean allTargets = true;
        if (policy.targets() != null && !policy.targets().contains(ALL_TARGETS)) {
            allTargets = false;
            targets = new ArrayList<>();
            for (String name : policy.targets()) {
                ChannelTarget target = name == null ? null : targetsByName.get(name.trim().toLowerCase(Locale.ROOT));
                if (target == null) {
                    errors.add(where + ".targets: '" + clip(name) + "' is not a target of this integration.");
                    continue;
                }
                if (!targets.contains(target.getName())) {
                    targets.add(target.getName());
                }
            }
        } else if (policy.targets() != null) {
            targets = List.of(ALL_TARGETS);
        }

        List<String> tools = null;
        if (policy.tools() != null) {
            if (policy.tools().size() > MAX_TOOLS_PER_POLICY) {
                errors.add(where + ".tools: at most " + MAX_TOOLS_PER_POLICY + " tool names.");
            }
            Set<String> names = new LinkedHashSet<>();
            for (String tool : policy.tools()) {
                String name = tool == null ? "" : tool.trim();
                if (name.isEmpty() || name.length() > MAX_TOOL_NAME_LENGTH || name.chars().anyMatch(c -> c < 0x21 || c == 0x7F)) {
                    errors.add(where + ".tools: '" + clip(tool) + "' is not a tool name.");
                    continue;
                }
                names.add(name);
            }
            if (ROLE_ANONYMOUS.equals(role) && !names.isEmpty()) {
                errors.add(where + ".tools: an unauthenticated sender never gets tools; leave tools out or empty.");
            }
            tools = List.copyOf(names);
            // A group discussion runs its members' own tools; the restriction cannot be
            // carried into it, so a restricted role must not name a group target.
            if (targets != null && !allTargets) {
                for (String name : targets) {
                    ChannelTarget target = targetsByName.get(name.toLowerCase(Locale.ROOT));
                    if (target != null && target.getType() == ChannelTarget.TargetType.GROUP) {
                        errors.add(where + ": target '" + name + "' is a group, and a role with a tools restriction cannot reach a "
                                + "group (its members use their own tools). Remove the target or the tools restriction.");
                    }
                }
            }
        }

        String approvalPolicy = policy.approvalPolicy() == null ? null : policy.approvalPolicy().trim();
        if (approvalPolicy != null && !APPROVAL_NONE.equals(approvalPolicy)) {
            errors.add(where + ".approvalPolicy: '" + clip(approvalPolicy) + "' needs the email approval gate, which this version does "
                    + "not have yet; only 'none' is accepted. Restrict the role's tools instead.");
        }
        if (Boolean.TRUE.equals(policy.sendOut())) {
            errors.add(where + ".sendOut: send-out to third parties is not available in this version; leave it out or false.");
        }

        Integer cap = policy.maxTurnsPerSenderPerDay();
        if (cap != null) {
            if (cap < 0) {
                errors.add(where + ".maxTurnsPerSenderPerDay must not be negative; 0 or absent inherits the integration's.");
            } else if (cap > 0 && integrationCap > 0 && cap > integrationCap) {
                errors.add(where + ".maxTurnsPerSenderPerDay (" + cap + ") is above the integration's maxTurnsPerSenderPerDay ("
                        + integrationCap + "); a role can only lower it.");
            }
        }
        return new RolePolicy(targets, tools, approvalPolicy, policy.sendOut(), cap == null || cap == 0 ? null : cap);
    }

    /**
     * Human-readable differences between two controls, for the audit entry: roles
     * added, removed or changed, policies added, removed or changed.
     */
    public static List<String> diff(EmailControl before, EmailControl after) {
        List<String> changes = new ArrayList<>();
        Map<String, SenderRole> oldRoles = rolesByName(before);
        Map<String, SenderRole> newRoles = rolesByName(after);
        for (var entry : newRoles.entrySet()) {
            SenderRole old = oldRoles.get(entry.getKey());
            if (old == null) {
                changes.add("role+:" + entry.getKey());
            } else if (!Objects.equals(old.senders(), entry.getValue().senders())) {
                changes.add("role~:" + entry.getKey());
            }
        }
        for (String name : oldRoles.keySet()) {
            if (!newRoles.containsKey(name)) {
                changes.add("role-:" + name);
            }
        }
        if (!new ArrayList<>(oldRoles.keySet()).equals(new ArrayList<>(newRoles.keySet()))
                && new HashSet<>(oldRoles.keySet()).equals(new HashSet<>(newRoles.keySet()))) {
            changes.add("order");
        }
        Map<String, RolePolicy> oldPolicies = before == null || before.getRolePolicies() == null ? Map.of() : before.getRolePolicies();
        Map<String, RolePolicy> newPolicies = after == null || after.getRolePolicies() == null ? Map.of() : after.getRolePolicies();
        for (var entry : newPolicies.entrySet()) {
            RolePolicy old = oldPolicies.get(entry.getKey());
            if (old == null) {
                changes.add("policy+:" + entry.getKey());
            } else if (!old.equals(entry.getValue())) {
                changes.add("policy~:" + entry.getKey());
            }
        }
        for (String name : oldPolicies.keySet()) {
            if (!newPolicies.containsKey(name)) {
                changes.add("policy-:" + name);
            }
        }
        return changes;
    }

    private static Map<String, SenderRole> rolesByName(EmailControl control) {
        Map<String, SenderRole> byName = new LinkedHashMap<>();
        if (control != null && control.getSenderRoles() != null) {
            for (SenderRole role : control.getSenderRoles()) {
                if (role != null && role.role() != null) {
                    byName.putIfAbsent(role.role(), role);
                }
            }
        }
        return byName;
    }

    private static String clip(String value) {
        if (value == null) {
            return "null";
        }
        String stripped = value.strip();
        return stripped.length() > 80 ? stripped.substring(0, 80) + "…" : stripped;
    }
}
