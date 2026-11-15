/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.HandoffPolicies;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptPolicy;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.AcceptRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.DecisionRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.GroupHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.TeamHandoffConfig;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Save-time (and import-time: imports go through the stores) validation of the
 * handoff configuration. <b>Shape only.</b> Whether a target group exists is
 * deliberately not checked, so an agent ZIP whose target group is missing on
 * this instance still imports; the runtime drops an unresolvable target.
 * <p>
 * A violation is an {@link IllegalArgumentException}, which the store layer
 * answers with a 400 like the other configuration checks, and the message
 * carries the offending field path.
 */
public final class HandoffConfigValidator {

    private HandoffConfigValidator() {
    }

    /** Validates a group's {@code handoffConfig}; {@code null} is valid. */
    public static void validate(AgentGroupConfiguration config) {
        if (config == null || config.getHandoffConfig() == null) {
            return;
        }
        GroupHandoffConfig handoff = config.getHandoffConfig();
        String path = "handoffConfig";
        Set<String> aliases = validateTargets(handoff.targets(), path + ".targets");
        validateParticipants(handoff.toolParticipants(), path + ".toolParticipants");
        validateDecisionRules(handoff.onDecision(), aliases, path + ".onDecision");
        validateAccept(handoff.accept(), path + ".accept");
    }

    /** Validates an LLM task's {@code teamHandoff}; {@code null} is valid. */
    public static void validateTask(TeamHandoffConfig config, String fieldPath) {
        if (config == null) {
            return;
        }
        validateTargets(config.targets(), fieldPath + ".targets");
    }

    private static Set<String> validateTargets(List<HandoffTarget> targets, String path) {
        Set<String> aliases = new HashSet<>();
        if (targets.size() > HandoffPolicies.MAX_TARGETS) {
            throw new IllegalArgumentException(path + " has " + targets.size() + " entries; at most " + HandoffPolicies.MAX_TARGETS + " are allowed");
        }
        for (int i = 0; i < targets.size(); i++) {
            HandoffTarget target = targets.get(i);
            String at = path + "[" + i + "]";
            if (target == null) {
                throw new IllegalArgumentException(at + " is null");
            }
            if (target.alias() == null || !HandoffPolicies.ALIAS.matcher(target.alias()).matches()) {
                throw new IllegalArgumentException(at + ".alias must be 1-40 characters of a-z, 0-9 or '-'");
            }
            if (!aliases.add(target.alias())) {
                throw new IllegalArgumentException(at + ".alias '" + target.alias() + "' is declared twice");
            }
            if (target.groupId() == null || target.groupId().isBlank()) {
                throw new IllegalArgumentException(at + ".groupId is required");
            }
            if (target.description() != null && target.description().length() > HandoffPolicies.MAX_DESCRIPTION_OF_TARGET) {
                throw new IllegalArgumentException(at + ".description is longer than " + HandoffPolicies.MAX_DESCRIPTION_OF_TARGET + " characters");
            }
        }
        return aliases;
    }

    private static void validateParticipants(String value, String path) {
        boolean ok = HandoffPolicies.PARTICIPANTS_ALL.equals(value) || HandoffPolicies.PARTICIPANTS_MODERATOR.equals(value)
                || (value.startsWith(HandoffPolicies.PARTICIPANTS_ROLE_PREFIX)
                        && !value.substring(HandoffPolicies.PARTICIPANTS_ROLE_PREFIX.length()).isBlank());
        if (!ok) {
            throw new IllegalArgumentException(path + " must be ALL, MODERATOR or ROLE:<role>, got '" + value + "'");
        }
    }

    private static void validateDecisionRules(List<DecisionRule> rules, Set<String> aliases, String path) {
        if (rules.size() > HandoffPolicies.MAX_DECISION_RULES) {
            throw new IllegalArgumentException(
                    path + " has " + rules.size() + " rules; at most " + HandoffPolicies.MAX_DECISION_RULES + " are allowed");
        }
        for (int i = 0; i < rules.size(); i++) {
            DecisionRule rule = rules.get(i);
            String at = path + "[" + i + "]";
            if (rule == null) {
                throw new IllegalArgumentException(at + " is null");
            }
            if (rule.target() == null || !aliases.contains(rule.target())) {
                throw new IllegalArgumentException(at + ".target '" + rule.target() + "' is not an alias declared in handoffConfig.targets");
            }
            if (rule.decisionTypes().isEmpty()) {
                throw new IllegalArgumentException(at + ".decisionTypes is empty: name at least one of VERDICT, VOTE, AGREEMENT, AWARD");
            }
            for (String type : rule.decisionTypes()) {
                if (!isDecisionType(type) || DecisionType.NONE.name().equals(type)) {
                    throw new IllegalArgumentException(at + ".decisionTypes has '" + type + "'; allowed: VERDICT, VOTE, AGREEMENT, AWARD");
                }
            }
            if (rule.subjectTemplate() == null || rule.subjectTemplate().isBlank()) {
                throw new IllegalArgumentException(at + ".subjectTemplate is required");
            }
            requireTemplateLength(rule.subjectTemplate(), at + ".subjectTemplate");
            requireTemplateLength(rule.descriptionTemplate(), at + ".descriptionTemplate");
        }
    }

    private static boolean isDecisionType(String type) {
        if (type == null) {
            return false;
        }
        try {
            DecisionType.valueOf(type);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void requireTemplateLength(String template, String path) {
        if (template != null && template.length() > HandoffPolicies.MAX_TEMPLATE_CHARS) {
            throw new IllegalArgumentException(path + " is longer than " + HandoffPolicies.MAX_TEMPLATE_CHARS + " characters");
        }
    }

    private static void validateAccept(AcceptPolicy accept, String path) {
        if (accept == null) {
            return;
        }
        if (accept.from().size() > HandoffPolicies.MAX_ACCEPT_RULES) {
            throw new IllegalArgumentException(
                    path + ".from has " + accept.from().size() + " entries; at most " + HandoffPolicies.MAX_ACCEPT_RULES + " are allowed");
        }
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < accept.from().size(); i++) {
            AcceptRule rule = accept.from().get(i);
            String at = path + ".from[" + i + "]";
            if (rule == null) {
                throw new IllegalArgumentException(at + " is null");
            }
            String source = rule.source();
            if (source != null && source.contains("*")) {
                throw new IllegalArgumentException(at + ".source '" + source + "' is a wildcard; name an exact agent:<id> or group:<id>");
            }
            if (source == null || !HandoffPolicies.SOURCE.matcher(source).matches() || source.endsWith(":")) {
                throw new IllegalArgumentException(at + ".source must be agent:<id> or group:<id>, got '" + source + "'");
            }
            if (!seen.add(source)) {
                throw new IllegalArgumentException(at + ".source '" + source + "' is listed twice");
            }
        }
    }
}
