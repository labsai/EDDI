/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.handoff;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.DecisionRule;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.GroupHandoffConfig;
import ai.labs.eddi.configs.groups.model.HandoffPolicies.HandoffTarget;
import ai.labs.eddi.configs.groups.model.HandoffRunState;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.SourceKind;
import ai.labs.eddi.configs.groups.model.WorkspaceHandoffs.Trigger;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic handoffs: files a task into another team when a discussion
 * completes with a matching structured decision
 * ({@code handoffConfig.onDecision}), so a VOTE winner reaches the next team
 * without relying on an LLM to remember to call the tool.
 * <p>
 * <b>Idempotent by construction.</b> Each rule's dedupe key is
 * {@code decision:<discussion>:<round>:<rule index>}, so a completion processed
 * twice (a retried persist, a resumed leg) files once. Matching is on the
 * decision's {@code type}, never on the method that produced it, so decisions
 * from any producer (VOTE, VERDICT, AGREEMENT, AWARD, a human tie-break) are
 * handled alike.
 * <p>
 * <b>Decision data is data.</b> Templates are a closed set of
 * {@code {decision.*}} placeholders substituted in one pass; the substituted
 * values are never parsed or evaluated as a template, so a winner string cannot
 * smuggle in {@code {#include}} or another placeholder.
 * <p>
 * Never throws: a handoff problem must not fail a discussion that completed.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class DecisionHandoffs {

    private static final Logger LOGGER = Logger.getLogger(DecisionHandoffs.class);

    static final Pattern PLACEHOLDER = Pattern.compile("\\{decision\\.(winner|outcome|type|method|phase)}");
    static final int MAX_SUBSTITUTED_CHARS = 1500;

    private final TeamHandoffService service;

    @Inject
    public DecisionHandoffs(TeamHandoffService service) {
        this.service = service;
    }

    /**
     * Files every matching rule of {@code config}.
     *
     * @param gc
     *            the discussion that just completed
     * @param config
     *            the group config the discussion ran under
     * @param listener
     *            streaming listener, may be {@code null}
     */
    public void afterCompleted(GroupConversation gc, AgentGroupConfiguration config, GroupDiscussionEventListener listener) {
        try {
            GroupHandoffConfig handoff = config != null ? config.getHandoffConfig() : null;
            DecisionRecord decision = gc != null ? gc.getDecision() : null;
            if (handoff == null || handoff.onDecision().isEmpty() || decision == null || decision.type() == null) {
                return;
            }
            List<DecisionRule> rules = handoff.onDecision();
            for (int i = 0; i < rules.size(); i++) {
                fileRule(gc, handoff, rules.get(i), i, decision, listener);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf("Decision handoffs for group conversation %s failed: %s", LogSanitizer.sanitize(gc != null ? gc.getId() : null),
                    e.getMessage());
        }
    }

    private void fileRule(GroupConversation gc, GroupHandoffConfig handoff, DecisionRule rule, int index, DecisionRecord decision,
                          GroupDiscussionEventListener listener) {
        if (!rule.decisionTypes().contains(decision.type().name())) {
            return;
        }
        if (rule.requireWinner() && (decision.winner() == null || decision.winner().isBlank())) {
            return;
        }
        HandoffTarget target = handoff.targetByAlias(rule.target());
        if (target == null) {
            LOGGER.warnf("onDecision[%d] of group %s names unknown target '%s'", index, LogSanitizer.sanitize(gc.getGroupId()),
                    LogSanitizer.sanitize(rule.target()));
            return;
        }
        String subject = abbreviate(render(rule.subjectTemplate(), decision), 200);
        String description = abbreviate(render(rule.descriptionTemplate(), decision), handoff.maxDescriptionChars());
        HandoffRunState state = gc.handoffStateOrCreate();
        int priority = rule.priority() != null ? rule.priority() : target.defaultPriority();
        String dedupeKey = "decision:" + gc.getId() + ":" + gc.getRound() + ":" + index;
        String decisionRef = decision.type().name() + (decision.decidedAtPhase() != null ? "@" + decision.decidedAtPhase() : "");

        HandoffRequest request = new HandoffRequest(SourceKind.GROUP, gc.getGroupId(), null, gc.getId(), null, gc.getUserId(), target.groupId(),
                subject,
                description, priority, Trigger.DECISION, decisionRef, null, null, state.getHop(), state.getLineage(), dedupeKey,
                handoff.maxDescriptionChars(),
                null);
        HandoffResult result = service.file(request);

        state.recordFiled(new HandoffRunState.FiledHandoff(result.handoffId(), target.alias(), target.groupId(), result.outcome().name(), subject));
        if (listener != null) {
            try {
                listener.onTeamHandoff(new GroupConversationEventSink.TeamHandoffEvent(result.handoffId(), target.alias(), target.groupId(),
                        result.outcome().name(), null, Trigger.DECISION.name(), subject));
            } catch (RuntimeException e) {
                LOGGER.debugf("Listener rejected team_handoff event: %s", e.getMessage());
            }
        }
    }

    /**
     * One-pass substitution of {@code {decision.*}}. {@code Matcher} appends each
     * value quoted, and the output is never scanned again.
     */
    static String render(String template, DecisionRecord decision) {
        if (template == null) {
            return "";
        }
        Map<String, String> values = Map.of("winner", nullToEmpty(decision.winner()), "outcome", nullToEmpty(decision.outcome()), "type",
                decision.type().name(), "method", nullToEmpty(decision.method()), "phase", nullToEmpty(decision.decidedAtPhase()));
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value.length() > MAX_SUBSTITUTED_CHARS) {
                value = value.substring(0, MAX_SUBSTITUTED_CHARS);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String abbreviate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 1)) + "…";
    }
}
