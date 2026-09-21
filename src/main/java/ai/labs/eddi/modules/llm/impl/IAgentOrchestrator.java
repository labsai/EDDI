/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;

import java.util.List;

/**
 * The tool-calling agent loop, as a contract (R2 step 6): run a turn's tools,
 * or resume one that paused for human approval.
 * <p>
 * Two methods, because there are exactly two ways into the loop. Everything
 * else {@code AgentOrchestrator} exposes — {@code buildToolSetup}, the assorted
 * gate/budget/reflection helpers — is either internal or reached by tests, and
 * putting it here would turn a seam into a second copy of the class's surface.
 * <p>
 * Lives in {@code ai.labs.eddi.modules.llm.impl} rather than an {@code api}
 * package on purpose: {@link AgentOrchestrator.ExecutionResult} is
 * package-private, and promoting it purely to satisfy an interface's placement
 * would widen a genuinely internal type. Every consumer ({@code LlmTask},
 * {@code CascadingModelExecutor}) already lives here.
 * <p>
 * Package-private, like {@code AgentOrchestrator} itself — a public interface
 * whose methods return a package-private record would compile but could not be
 * called from outside anyway, which is a worse kind of false advertising than
 * simply matching the visibility that already exists.
 * <p>
 * The point of the interface is substitutability at the call sites — a test or
 * a future decorator (tracing, a recording harness, a per-tenant policy
 * wrapper) can stand in for the orchestrator without subclassing a 30-parameter
 * constructor. The 13 orchestrator test classes are unaffected: they construct
 * the concrete class and keep reaching its package-private internals, which is
 * why this is a pure-move commit and not a migration.
 */
interface IAgentOrchestrator {

    /**
     * Runs the tool-calling loop for a turn — discovers and registers every tool
     * source, then drives the model until it stops requesting tools, the iteration
     * budget runs out, or a gated call pauses the turn for human approval.
     *
     * @param effectiveToolApprovals
     *            the task-scoped tool-approval config if the task set one, else the
     *            agent-level default; the exact config a pause records
     * @param transcriptMaxBytes
     *            cap for freezing the transcript into a
     *            {@link PendingToolCallBatch} should this turn re-pause
     * @param jsonPolicy
     *            decides per request whether {@code ResponseFormat.JSON} is set
     */
    AgentOrchestrator.ExecutionResult executeIfToolsEnabled(ChatModel chatModel, String systemMessage,
                                                            List<ChatMessage> chatMessages, LlmConfiguration.Task task, IConversationMemory memory,
                                                            ToolApprovalsConfig effectiveToolApprovals, int llmTaskIndex, int transcriptMaxBytes,
                                                            JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException;

    /**
     * Resumes a turn that paused on a gated tool call, applying the human's
     * verdicts and continuing the same loop from the iteration that paused.
     *
     * @param batch
     *            the durable snapshot taken at pause time — transcript, gated
     *            calls, iteration index, and the tool-approval config that gated it
     * @param decision
     *            the human's verdict, either one blanket outcome or per-call
     */
    AgentOrchestrator.ExecutionResult resumeToolLoop(ChatModel chatModel, LlmConfiguration.Task task,
                                                     IConversationMemory memory, PendingToolCallBatch batch, HitlDecision decision,
                                                     boolean toolHitlEnabled,
                                                     JsonResponseFormatPolicy jsonPolicy)
            throws LifecycleException;
}
