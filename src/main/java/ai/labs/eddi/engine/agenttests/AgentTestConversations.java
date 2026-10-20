/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

/**
 * What marks a conversation as an agent-test conversation: its user id.
 * <p>
 * The test runner starts every conversation as user
 * {@value #USER_ID_PREFIX}{@code <runId>:<case>:<repetition>} in the
 * {@code test} environment. The user id is the marker because it is the one
 * piece of identity a conversation carries from its very first instruction —
 * the {@code CONVERSATION_START} turn runs inside {@code startConversation},
 * before the conversation has an id — and because it travels unchanged into
 * every place that has to behave differently for a test:
 * <ul>
 * <li>long-term user memory is neither loaded nor written
 * ({@code ConversationService.createPropertiesHandler}) — a test must not
 * change, nor depend on, what real users have stored;</li>
 * <li>tool calls are mocked or refused rather than executed
 * ({@code ToolMockRegistry}).</li>
 * </ul>
 * Anyone may start a conversation with such a user id, and gets exactly that:
 * no user memory and no live tool calls. Nothing is granted by the prefix, only
 * taken away, so it does not have to be unforgeable.
 *
 * @since 6.5.0
 */
public final class AgentTestConversations {

    /** Reserved user id prefix of agent-test conversations. */
    public static final String USER_ID_PREFIX = "agent-test:";

    /** The {@code system:*} actor test conversations are ended as. */
    public static final String SYSTEM_ACTOR = "system:agent-test";

    private AgentTestConversations() {
    }

    /** A plain prefix check: the whole cost for every non-test conversation. */
    public static boolean isTestUserId(String userId) {
        return userId != null && userId.startsWith(USER_ID_PREFIX);
    }

    /** The user id of one repetition of one case of a run. */
    public static String userIdFor(String runId, int caseIndex, int repetition) {
        return USER_ID_PREFIX + runId + ":" + caseIndex + ":" + repetition;
    }
}
