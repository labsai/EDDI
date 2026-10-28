/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.AlertEvent;
import ai.labs.eddi.engine.alerts.model.AlertMatch;
import ai.labs.eddi.integrations.slack.SlackMrkdwn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What leaves EDDI when an alert is delivered — the webhook JSON and the Slack
 * message — built in one place so the privacy rule is enforced in one place.
 * <p>
 * <b>Allow-list, not redaction.</b> The payload is assembled field by field
 * from the event: the rule, the agent and version, the conversation id, the
 * error kind or the rating's score, scale and tags, the criteria that matched
 * and links into the Manager. Nothing is copied wholesale, so a field added to
 * the event later does not leak by default. There is no user id anywhere on the
 * event, and no conversation text; the only user-written text is the rating
 * comment excerpt (at most {@link AlertMatch#MAX_COMMENT_EXCERPT} characters).
 */
public final class AlertPayloads {

    /** Version of the webhook payload shape, sent as {@code schema}. */
    public static final String SCHEMA = "eddi.alert.v1";

    private AlertPayloads() {
    }

    /** The webhook body. */
    public static Map<String, Object> webhook(AlertEvent event, Optional<String> managerUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schema", SCHEMA);
        body.put("id", event.id());
        body.put("kind", event.kind().name());
        body.put("firedAt", event.firedAt() == null ? null : event.firedAt().toString());
        body.put("ruleId", event.ruleId());
        body.put("ruleName", event.ruleName());
        body.put("source", event.source() == null ? null : event.source().name());
        body.put("agentId", event.agentId());
        body.put("agentVersion", event.agentVersion());
        body.put("conversationId", event.conversationId());
        body.put("summary", event.summary());
        if (event.match() != null) {
            body.put("match", match(event.match()));
        }
        if (event.kind() == AlertEvent.Kind.DIGEST) {
            body.put("digestCount", event.digestCount());
            body.put("digestItems", event.digestItems());
        }
        Map<String, String> links = links(event, managerUrl);
        if (!links.isEmpty()) {
            body.put("links", links);
        }
        return body;
    }

    private static Map<String, Object> match(AlertMatch match) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("matched", match.matched());
        if (match.errorKind() != null) {
            out.put("errorKind", match.errorKind().name());
        }
        if (match.value() != null) {
            out.put("value", match.value());
        }
        if (match.ratingId() != null) {
            out.put("ratingId", match.ratingId());
            out.put("stepIndex", match.stepIndex());
            out.put("scale", match.scale());
            out.put("score", match.score());
            out.put("normalizedScore", match.normalizedScore());
            out.put("tags", match.tags());
            if (match.commentExcerpt() != null) {
                out.put("commentExcerpt", match.commentExcerpt());
            }
        }
        return out;
    }

    /** Manager links, when the Manager's URL is configured. */
    static Map<String, String> links(AlertEvent event, Optional<String> managerUrl) {
        Map<String, String> links = new LinkedHashMap<>();
        managerUrl.ifPresent(base -> {
            links.put("alert", base + "/manage/alerts?event=" + event.id());
            if (event.conversationId() != null) {
                links.put("conversation", base + "/manage/conversationview/" + event.conversationId());
            }
        });
        return links;
    }

    /** The Slack message text (mrkdwn); the same facts as the webhook body. */
    public static String slackText(AlertEvent event, Optional<String> managerUrl) {
        List<String> lines = new ArrayList<>();
        lines.add((event.kind() == AlertEvent.Kind.DIGEST ? ":bell: *Alert digest* — " : ":rotating_light: *Alert* — ") + escape(event.summary()));
        AlertMatch match = event.match();
        if (match != null && match.commentExcerpt() != null) {
            lines.add("> " + escape(match.commentExcerpt()).replace("\n", "\n> "));
        }
        if (match != null && match.value() != null) {
            lines.add("Value: `" + escape(match.value()).replace("`", "'") + "`");
        }
        if (match != null && match.tags() != null && !match.tags().isEmpty()) {
            lines.add("Tags: " + escape(String.join(", ", match.tags())));
        }
        if (event.kind() == AlertEvent.Kind.DIGEST && event.digestItems() != null) {
            for (String item : event.digestItems()) {
                lines.add("• " + escape(item));
            }
            if (event.digestCount() != null && event.digestCount() > event.digestItems().size()) {
                lines.add("… and " + (event.digestCount() - event.digestItems().size()) + " more");
            }
        }
        if (event.conversationId() != null) {
            lines.add("Conversation: `" + escape(event.conversationId()) + "`");
        }
        Map<String, String> links = links(event, managerUrl);
        if (links.containsKey("alert")) {
            lines.add("<" + links.get("alert") + "|Open in the Manager>");
        }
        return String.join("\n", lines);
    }

    /**
     * Slack control characters, so rule names and comments cannot inject mentions
     * or links.
     */
    static String escape(String text) {
        return text == null ? "" : SlackMrkdwn.escape(text);
    }
}
