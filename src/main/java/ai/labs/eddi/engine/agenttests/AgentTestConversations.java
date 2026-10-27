/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import java.util.Locale;

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
 * Nothing is granted by the prefix, only taken away. But a run's mocks and its
 * record of tool calls are keyed on these user ids, so a conversation started
 * from outside with a live run's id would join that run's session and write
 * into its results: a caller-supplied user id in this namespace is refused at
 * external conversation start ({@code OwnershipValidator}: REST, MCP, group
 * conversations — the requested and the resolved id both), at schedule save
 * ({@code RestScheduleStore}) and at every schedule fire
 * ({@code ScheduleFireExecutor}), whoever the caller and whether or not
 * authorization is on; a signed-in principal with such a name is refused at
 * authentication ({@code ReservedPrincipals}). The runner starts its
 * conversations through {@code IConversationService} directly, below those
 * checks.
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

    /**
     * Whether a user id supplied from outside the runner falls in the reserved
     * namespace — compared ignoring case and surrounding blanks, as
     * {@code ReservedPrincipals} does, so no spelling of it gets through.
     */
    public static boolean isReservedUserId(String userId) {
        return userId != null && userId.strip().toLowerCase(Locale.ROOT).startsWith(USER_ID_PREFIX);
    }

    /** The user id of one repetition of one case of a run. */
    public static String userIdFor(String runId, int caseIndex, int repetition) {
        return USER_ID_PREFIX + runId + ":" + caseIndex + ":" + repetition;
    }
}
