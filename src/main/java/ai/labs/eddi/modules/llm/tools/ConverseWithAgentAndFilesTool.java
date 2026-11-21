/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.enterprise.inject.Vetoed;

import java.util.List;
import java.util.Objects;

/**
 * {@code converseWithAgent} with a fourth, optional argument: names of files of
 * this conversation to hand to the agent.
 * <p>
 * A second class with the same tool name, because langchain4j reflects one
 * specification per {@code @Tool} method and an argument cannot be present
 * conditionally. {@code DynamicAgentToolsProvider} assembles exactly one of the
 * two: this one only when the agent's {@code documents.sharing.delegation} is
 * on and the conversation's memory shows a stored file; otherwise
 * {@link ConverseWithAgentTool}, whose specification stays as it was. Every
 * guardrail is the same object's: this class only forwards to the
 * {@link ConverseWithAgentTool} it wraps.
 * <p>
 * Approval patterns, the whitelist slug and the result cache treat it exactly
 * like {@link ConverseWithAgentTool}: source {@code dynamic}, canonical name
 * {@code converse_with_agent}, never cached ({@code ToolObjectReflector},
 * {@code ToolNameResolver}, {@code ToolCacheService}).
 *
 * @since 6.7.0
 */
@Vetoed // Instantiated per-invocation by DynamicAgentToolsProvider — must NOT be a CDI
        // bean
public class ConverseWithAgentAndFilesTool {

    private final ConverseWithAgentTool core;

    /**
     * @param core
     *            the delegation tool this one extends, built with an
     *            {@link AttachmentShareBinding}
     */
    public ConverseWithAgentAndFilesTool(ConverseWithAgentTool core) {
        this.core = Objects.requireNonNull(core, "core");
    }

    @Tool("Send a message to another deployed EDDI agent and receive its response. "
            + "Use this for inter-agent delegation or consultation. "
            + "Provide an existing conversationId for multi-turn conversations, "
            + "or omit it to start a new conversation. "
            + "Files of this conversation can be handed to the agent by name; they reach it as if its user had attached them. "
            + "Files can only be handed to a conversation this conversation started.")
    public String converseWithAgent(
                                    @P("The ID of the target agent to converse with") String agentId,
                                    @P("The message to send to the agent") String message,
                                    @P("Optional conversation ID for continuing a multi-turn conversation") String conversationId,
                                    @P(value = "Optional: names of files from this conversation to hand to the agent (see listAttachments).",
                                       required = false) List<String> attachments) {
        return core.converse(agentId, message, conversationId, attachments);
    }
}
