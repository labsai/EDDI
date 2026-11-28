/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.engine.agenttests.AgentTestConversations;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.internal.DreamService;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.engine.security.ClientContextGuard;
import ai.labs.eddi.integrations.email.EmailInboundProcessor;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.openai.OpenAiMessageMapper;
import ai.labs.eddi.integrations.slack.SlackUserIdentity;
import ai.labs.eddi.integrations.webhook.WebhookEventHandler;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;

import java.util.List;

/**
 * Where a turn came from — the {@code origin} of a health turn row.
 * <p>
 * <b>Classified per turn, from what the turn stored</b>: the conversation's
 * user id and the step's own {@code context:*} entries. The first rule that
 * matches wins (see {@link #classify}). Nothing is guessed from the request:
 * the same stored step always yields the same origin, so a row can be
 * recomputed. A conversation's origin, for outcomes, is its first row that is
 * not {@link #API} — a {@code new}-strategy schedule conversation, for one,
 * starts with a turn that carries nothing but its fires do.
 * <p>
 * Three origins cannot be claimed by a client: {@link #AGENT_TEST} rests on a
 * user id no request may use ({@code ReservedPrincipals}), and
 * {@link #GROUP_MEMBER} and {@link #DELEGATED} on engine-reserved context keys,
 * which {@link ClientContextGuard} strips from client input. The others can be
 * imitated by a client setting the same context key, which can only mislabel
 * that client's own conversation: origin is a reporting dimension, never a
 * permission.
 * <p>
 * Not distinguishable server-side today, and therefore not separate origins:
 * the Chat UI and the Manager (both use the same REST API, {@link #API}), and
 * the stateless and stateful {@code /v1} modes ({@link #V1}).
 */
public final class HealthOrigins {

    public static final String AGENT_TEST = "agent-test";
    public static final String GROUP_MEMBER = "group-member";
    public static final String DELEGATED = "delegated";
    public static final String SCHEDULE = "schedule";
    public static final String A2A = "a2a";
    public static final String EMAIL = "email";
    public static final String SLACK = "slack";
    public static final String WEBHOOK = "webhook";
    public static final String V1 = "v1";
    public static final String MCP = "mcp";
    public static final String API = "api";

    /** Every origin, in classification order. */
    public static final List<String> ALL = List.of(AGENT_TEST, GROUP_MEMBER, DELEGATED, SCHEDULE, A2A, EMAIL, SLACK, WEBHOOK, V1, MCP, API);

    /**
     * Origins whose conversations are people talking to the agent — the ones
     * effectiveness measures are computed over by default.
     */
    public static final List<String> INTERACTIVE = List.of(EMAIL, SLACK, V1, MCP, API);

    // Context keys the integrations write; literals where the owning constant is
    // not public. Kept in step by HealthOriginsTest.
    static final String CONTEXT_SCHEDULE = "schedule";
    static final String CONTEXT_A2A_TASK = "a2aTaskId";
    static final String CONTEXT_SLACK = "slack";
    static final String CONTEXT_MCP = "mcp";
    static final String CONTEXT_WEBHOOK_INTEGRATION = "webhookIntegration";
    static final String CONTEXT_CHANNEL_INTENT = "channelIntent";
    static final String OPENAI_INTENT_PREFIX = "channel:openai:";
    static final String A2A_USER_PREFIX = "a2a:";

    private HealthOrigins() {
    }

    /**
     * The origin of the turn that ran {@code step} in a conversation owned by
     * {@code userId}. Never {@code null}.
     */
    public static String classify(String userId, IConversationStep step) {
        String user = userId == null ? "" : userId;
        if (user.startsWith(AgentTestConversations.USER_ID_PREFIX)) {
            return AGENT_TEST;
        }
        if (has(step, ClientContextGuard.KEY_GROUP_CONVERSATION_ID)) {
            return GROUP_MEMBER;
        }
        if (has(step, ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH)) {
            return DELEGATED;
        }
        if (has(step, CONTEXT_SCHEDULE) || has(step, ScheduleConfiguration.CONTEXT_MAX_COST_PER_FIRE)
                || DreamService.SCHEDULER_PLACEHOLDER_USER_ID.equals(user)) {
            return SCHEDULE;
        }
        if (has(step, CONTEXT_A2A_TASK) || user.startsWith(A2A_USER_PREFIX)) {
            return A2A;
        }
        if (EmailUserIdentity.isEmailIdentity(user) || has(step, EmailInboundProcessor.INTEGRATION_CONTEXT_KEY)) {
            return EMAIL;
        }
        if (user.startsWith(SlackUserIdentity.PREFIX) || has(step, CONTEXT_SLACK)) {
            return SLACK;
        }
        if (user.startsWith(WebhookEventHandler.USER_PREFIX) || has(step, CONTEXT_WEBHOOK_INTEGRATION)) {
            return WEBHOOK;
        }
        if (OpenAiMessageMapper.CHANNEL_NAME.equals(contextValue(step, OpenAiMessageMapper.CONTEXT_CHANNEL))
                || startsWith(contextValue(step, CONTEXT_CHANNEL_INTENT), OPENAI_INTENT_PREFIX)) {
            return V1;
        }
        if (has(step, CONTEXT_MCP)) {
            return MCP;
        }
        return API;
    }

    private static boolean has(IConversationStep step, String contextKey) {
        return step != null && step.getData(MemoryKeys.CONTEXT_PREFIX + ":" + contextKey) != null;
    }

    private static String contextValue(IConversationStep step, String contextKey) {
        if (step == null) {
            return null;
        }
        IData<Object> data = step.getData(MemoryKeys.CONTEXT_PREFIX + ":" + contextKey);
        if (data == null || data.getResult() == null) {
            return null;
        }
        Object result = data.getResult();
        Object value = result instanceof Context context ? context.getValue() : result;
        return value == null ? null : String.valueOf(value);
    }

    private static boolean startsWith(String value, String prefix) {
        return value != null && value.startsWith(prefix);
    }
}
