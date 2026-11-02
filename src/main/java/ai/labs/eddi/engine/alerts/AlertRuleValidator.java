/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertCondition;
import ai.labs.eddi.engine.alerts.model.AlertRule;
import ai.labs.eddi.engine.alerts.model.AlertScope;
import ai.labs.eddi.engine.alerts.model.AlertSource;
import ai.labs.eddi.engine.alerts.model.DeliveryTarget;
import ai.labs.eddi.engine.alerts.model.ErrorKind;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.model.SecretReference;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Save-time validation of an {@link AlertRule}: every problem is reported, each
 * naming its field, so a rule is never stored that would misbehave or silently
 * never fire. Access (who may scope or target what) is the REST layer's.
 */
public final class AlertRuleValidator {

    public static final int MAX_NAME_LENGTH = 120;
    public static final int MAX_AGENTS = 100;
    public static final int MAX_TAGS = 20;
    public static final int MAX_TAG_LENGTH = 60;
    public static final int MAX_TARGETS = 5;
    public static final int MAX_URL_LENGTH = 2048;
    public static final int MAX_WINDOW_MINUTES = 1440;
    public static final int MAX_PER_WINDOW = 1000;

    /**
     * A Slack channel or conversation id: C…, G… or D… plus upper-case
     * alphanumerics.
     */
    private static final Pattern SLACK_CHANNEL = Pattern.compile("[CGD][A-Z0-9]{2,30}");

    private AlertRuleValidator() {
    }

    /** @return the problems, empty when the rule is valid */
    public static List<String> validate(AlertRule rule) {
        List<String> problems = new ArrayList<>();
        if (rule == null) {
            problems.add("a rule body is required");
            return problems;
        }
        if (rule.name() == null || rule.name().isBlank()) {
            problems.add("name is required");
        } else if (rule.name().length() > MAX_NAME_LENGTH) {
            problems.add("name may be at most " + MAX_NAME_LENGTH + " characters");
        }
        validateScope(rule.scope(), problems);
        if (rule.source() == null) {
            problems.add("source is required: RATING, CONVERSATION_ERROR or OUTPUT_MATCH");
        } else {
            validateCondition(rule, problems);
        }
        validateDelivery(rule.delivery(), problems);
        if (rule.throttle() != null) {
            if (rule.throttle().windowMinutes() < 1 || rule.throttle().windowMinutes() > MAX_WINDOW_MINUTES) {
                problems.add("throttle.windowMinutes must be between 1 and " + MAX_WINDOW_MINUTES);
            }
            if (rule.throttle().maxPerWindow() < 1 || rule.throttle().maxPerWindow() > MAX_PER_WINDOW) {
                problems.add("throttle.maxPerWindow must be between 1 and " + MAX_PER_WINDOW);
            }
        }
        return problems;
    }

    private static void validateScope(AlertScope scope, List<String> problems) {
        if (scope == null) {
            problems.add("scope is required: allAgents, or agentIds");
            return;
        }
        if (scope.allAgents() && !scope.agentIds().isEmpty()) {
            problems.add("scope: set allAgents or agentIds, not both");
        }
        if (!scope.allAgents() && scope.agentIds().isEmpty()) {
            problems.add("scope.agentIds must name at least one agent (or set allAgents)");
        }
        if (scope.agentIds().size() > MAX_AGENTS) {
            problems.add("scope.agentIds may list at most " + MAX_AGENTS + " agents");
        }
        Set<String> seen = new HashSet<>();
        for (String agentId : scope.agentIds()) {
            if (agentId == null || agentId.isBlank()) {
                problems.add("scope.agentIds must not contain a blank id");
            } else if (!seen.add(agentId)) {
                problems.add("scope.agentIds lists " + agentId + " twice");
            }
        }
    }

    private static void validateCondition(AlertRule rule, List<String> problems) {
        AlertCondition condition = rule.condition();
        if (condition == null) {
            problems.add("condition is required");
            return;
        }
        boolean ratingFields = condition.maxNormalizedScore() != null || condition.onComment() != null
                || (condition.tags() != null && !condition.tags().isEmpty());
        boolean errorFields = condition.errorKinds() != null && !condition.errorKinds().isEmpty();
        if (rule.source() != AlertSource.OUTPUT_MATCH && condition.hasOutputMatchFields()) {
            problems.add("condition.path, op, value and includeValue belong to OUTPUT_MATCH rules");
        }
        switch (rule.source()) {
            case OUTPUT_MATCH -> {
                if (ratingFields || errorFields) {
                    problems.add("condition.maxNormalizedScore, onComment, tags and errorKinds do not belong to OUTPUT_MATCH rules");
                }
                problems.addAll(OutputMatcher.problems(condition));
            }
            case RATING -> {
                if (errorFields) {
                    problems.add("condition.errorKinds belongs to CONVERSATION_ERROR rules");
                }
                if (!condition.hasRatingCriteria()) {
                    problems.add("a RATING rule needs condition.maxNormalizedScore, condition.onComment=true or condition.tags");
                }
                Double max = condition.maxNormalizedScore();
                if (max != null && (max.isNaN() || max < 0 || max > 1)) {
                    problems.add("condition.maxNormalizedScore is a normalized score between 0 and 1");
                }
                if (condition.tags() != null) {
                    if (condition.tags().size() > MAX_TAGS) {
                        problems.add("condition.tags may list at most " + MAX_TAGS + " tags");
                    }
                    for (String tag : condition.tags()) {
                        if (tag == null || tag.isBlank() || tag.length() > MAX_TAG_LENGTH) {
                            problems.add("condition.tags: each tag is 1 to " + MAX_TAG_LENGTH + " characters");
                            break;
                        }
                    }
                }
            }
            case CONVERSATION_ERROR -> {
                if (ratingFields) {
                    problems.add("condition.maxNormalizedScore, onComment and tags belong to RATING rules");
                }
                if (!errorFields) {
                    problems.add("a CONVERSATION_ERROR rule needs condition.errorKinds (one or more of " + List.of(ErrorKind.values()) + ")");
                } else if (new HashSet<>(condition.errorKinds()).size() != condition.errorKinds().size()) {
                    problems.add("condition.errorKinds must not repeat a kind");
                }
            }
        }
    }

    private static void validateDelivery(List<DeliveryTarget> delivery, List<String> problems) {
        if (delivery.size() > MAX_TARGETS) {
            problems.add("delivery may list at most " + MAX_TARGETS + " targets (the Manager inbox is always included)");
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < delivery.size(); i++) {
            DeliveryTarget target = delivery.get(i);
            String field = "delivery[" + i + "]";
            if (target == null || target.type() == null) {
                problems.add(field + ".type is required: SLACK or WEBHOOK");
                continue;
            }
            switch (target.type()) {
                case SLACK -> {
                    if (target.channelId() == null || !SLACK_CHANNEL.matcher(target.channelId()).matches()) {
                        problems.add(field + ".channelId must be a Slack channel id such as C0123456789");
                    }
                    if (target.url() != null || target.signingSecret() != null) {
                        problems.add(field + ": url and signingSecret belong to WEBHOOK targets");
                    }
                }
                case WEBHOOK -> {
                    validateWebhookUrl(field, target.url(), problems);
                    if (target.signingSecret() == null || !SecretReference.isVaultReference(target.signingSecret())
                            || !target.signingSecret().strip().startsWith("${vault:") || !target.signingSecret().strip().endsWith("}")) {
                        problems.add(field + ".signingSecret must be a ${vault:...} reference — store the secret in the vault first");
                    }
                    if (target.channelId() != null) {
                        problems.add(field + ": channelId belongs to SLACK targets");
                    }
                }
            }
            if (target.type() != null && (target.channelId() != null || target.url() != null) && !keys.add(target.key())) {
                problems.add(field + " repeats an earlier target");
            }
        }
    }

    private static void validateWebhookUrl(String field, String url, List<String> problems) {
        if (url == null || url.isBlank()) {
            problems.add(field + ".url is required");
            return;
        }
        if (DeliveryTarget.isMaskedUrl(url)) {
            problems.add(field
                    + ".url is the masked value a read returns, and its targetId matches no stored target; send the targetId the read returned, or enter the URL again");
            return;
        }
        String trimmed = url.strip();
        if (trimmed.startsWith("${vault:")) {
            if (!trimmed.endsWith("}") || !SecretReference.isVaultReference(trimmed)) {
                problems.add(field + ".url: a vault reference must be ${vault:...}");
            }
            return;
        }
        if (url.length() > MAX_URL_LENGTH) {
            problems.add(field + ".url may be at most " + MAX_URL_LENGTH + " characters");
            return;
        }
        try {
            URI uri = UrlValidationUtils.validateUrlSyntax(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                problems.add(field + ".url must be an https URL");
            }
            if (UrlValidationUtils.isBlockedHostname(uri.getHost().toLowerCase(Locale.ROOT))) {
                problems.add(field + ".url points at a local or internal host");
            }
            if (uri.getUserInfo() != null) {
                problems.add(field + ".url must not carry credentials");
            }
        } catch (IllegalArgumentException e) {
            problems.add(field + ".url: " + e.getMessage());
        }
    }
}
