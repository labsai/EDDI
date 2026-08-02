/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ConversationRecallTool;
import ai.labs.eddi.modules.llm.tools.UserMemoryTool;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor;
import ai.labs.eddi.modules.llm.tools.impl.ReadAttachmentTool;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The three conversation-context-dependent tool sources — persistent user
 * memory, conversation recall (rolling-summary lookback), and attachment
 * reading (R2 step 2). Extracted from {@code AgentOrchestrator}'s
 * {@code addUserMemoryToolIfEnabled} /
 * {@code addConversationRecallToolIfEnabled} /
 * {@code addReadAttachmentToolIfEnabled} as a pure move — no behavior change.
 * <p>
 * Grouped into one provider rather than three because they share a single
 * defining property: each is enabled by what the <em>conversation</em>
 * currently holds (a user-memory config, an existing rolling summary,
 * attachments on this or an earlier turn) rather than by the built-in-tools
 * whitelist alone. Splitting them would produce three ~20-line classes with
 * identical construction and lifetime, which is bureaucracy, not modularity.
 * Their {@code toolSources} tags stay distinct regardless — {@code
 * ToolObjectReflector} derives those per tool object ({@code "memory"},
 * {@code "recall"}, {@code "builtin"}), so approval patterns are unaffected by
 * the grouping.
 * <p>
 * Constructed fresh per call: {@code attachmentStore} and
 * {@code attachmentTextExtractor} are {@code @Inject volatile} fields on
 * {@code AgentOrchestrator}, null until the container populates them after
 * construction — the same wrinkle {@link DynamicAgentToolsProvider} has.
 */
class ContextualToolsProvider implements ToolSourceProvider {

    private static final Logger LOGGER = Logger.getLogger(ContextualToolsProvider.class);

    private final IUserMemoryStore userMemoryStore;
    private final IAttachmentStore attachmentStore;
    private final AttachmentTextExtractor attachmentTextExtractor;

    ContextualToolsProvider(IUserMemoryStore userMemoryStore, IAttachmentStore attachmentStore,
            AttachmentTextExtractor attachmentTextExtractor) {
        this.userMemoryStore = userMemoryStore;
        this.attachmentStore = attachmentStore;
        this.attachmentTextExtractor = attachmentTextExtractor;
    }

    @Override
    public String source() {
        // Nominal tag only. Per-tool provenance ("memory"/"recall"/"builtin") is
        // derived by ToolObjectReflector from each tool object's own class.
        return "builtin";
    }

    /**
     * Mirrors, exactly, the enablement rules the live
     * {@code collectEnabledTools}/{@code collectAllBuiltInTools} path applies — NOT
     * simply "add all three". Getting this wrong grants tools an operator
     * deliberately switched off:
     * <ul>
     * <li>{@code enableBuiltInTools} null or false ⇒ <b>only</b>
     * {@code readAttachment}. Null means disabled, and null is the default. User
     * memory is a persistent cross-conversation <em>write</em> capability, so
     * handing it to an agent with built-ins off would be a real privilege
     * escalation, not a cosmetic difference.</li>
     * <li>a whitelist is configured ⇒ user memory only on {@code "usermemory"},
     * recall only on {@code "conversationRecall"}.</li>
     * <li>no whitelist ⇒ both, as today.</li>
     * </ul>
     * {@code readAttachment} is deliberately outside every gate — it is part of
     * attachment support rather than a built-in capability, and it self-gates on
     * the conversation actually having files.
     */
    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        List<Object> tools = new ArrayList<>();
        Boolean enableBuiltInTools = ctx.task().getEnableBuiltInTools();
        if (enableBuiltInTools != null && enableBuiltInTools) {
            if (ctx.hasNoWhitelist() || ctx.isWhitelisted("usermemory")) {
                addUserMemoryToolIfEnabled(tools, ctx.memory());
            }
            if (ctx.hasNoWhitelist() || ctx.isWhitelisted("conversationRecall")) {
                addConversationRecallToolIfEnabled(tools, ctx.task(), ctx.memory());
            }
        }
        addReadAttachmentToolIfEnabled(tools, ctx.memory());
        if (tools.isEmpty()) {
            return ToolContribution.empty();
        }
        var reflected = ToolObjectReflector.reflect(tools);
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of());
    }

    /**
     * Constructs and adds a UserMemoryTool if the agent has persistent user memory
     * enabled. The tool is created per-invocation with conversation-specific
     * context.
     */
    void addUserMemoryToolIfEnabled(List<Object> tools, IConversationMemory memory) {
        AgentConfiguration.UserMemoryConfig config = memory.getUserMemoryConfig();
        if (config == null || userMemoryStore == null)
            return;

        // Extract groupIds from conversation properties (injected by
        // GroupConversationService)
        List<String> groupIds = List.of();
        var props = memory.getConversationProperties();
        if (props != null) {
            Object groupIdProp = props.get("groupId");
            if (groupIdProp instanceof Property p && p.getValueString() != null) {
                groupIds = List.of(p.getValueString());
            }
        }

        var tool = new UserMemoryTool(userMemoryStore, memory.getUserId(), memory.getAgentId(), memory.getConversationId(), groupIds, config);
        tools.add(tool);
        LOGGER.infof("[MEMORY] UserMemoryTool enabled for agent='%s', user='%s', groups=%s", sanitize(memory.getAgentId()),
                sanitize(memory.getUserId()), groupIds.stream().map(g -> sanitize(g)).toList());
    }

    /**
     * Constructs and adds a ConversationRecallTool if a rolling summary is active.
     * The tool is created per-invocation with the conversation's output list and
     * the summary step boundary.
     */
    void addConversationRecallToolIfEnabled(List<Object> tools, LlmConfiguration.Task task, IConversationMemory memory) {
        // Only add if rolling summary is configured and a summary exists
        var summaryConfig = task.getConversationSummary();
        if (summaryConfig == null || !summaryConfig.isEnabled())
            return;

        String existingSummary = ConversationSummarizer.readSummary(memory);
        if (existingSummary == null)
            return;

        int throughStep = ConversationSummarizer.readSummaryThroughStep(memory);
        var tool = new ConversationRecallTool(List.copyOf(memory.getConversationOutputs()), throughStep, summaryConfig.getMaxRecallTurns());
        tools.add(tool);
        LOGGER.infof("[RECALL] ConversationRecallTool enabled: summaryThroughStep=%d, maxRecallTurns=%d", throughStep,
                summaryConfig.getMaxRecallTurns());
    }

    /**
     * Constructs and adds a {@link ReadAttachmentTool} when this conversation has
     * attachments — from this turn or any earlier one — giving the LLM on-demand
     * access to attachment text (recall of an earlier turn's file, oversize files
     * not inlined, page-targeted PDF reads). The conversation id is implicit — the
     * tool never takes it as a parameter.
     * <p>
     * Gating on the current turn alone defeated the tool's main purpose: a file is
     * inlined only on the turn it arrives, so a follow-up question about it reached
     * a model with neither the document nor any way to fetch it — and the model
     * would answer that no file had ever been shared.
     */
    void addReadAttachmentToolIfEnabled(List<Object> tools, IConversationMemory memory) {
        if (attachmentStore == null || attachmentTextExtractor == null) {
            return;
        }
        if (memory == null || memory.getCurrentStep() == null) {
            return;
        }
        // Exact-match read (getData, not the prefix-scanning getLatestData):
        // "attachments"
        // is a prefix of the attachments:extracts/errors keys the forwarder persists.
        IData<List<?>> attachmentData = memory.getCurrentStep().getData(MemoryKeys.ATTACHMENTS);
        // Coerced, not raw-counted: on a resumed turn these are maps, and a raw
        // count would also count entries that are not attachments at all.
        //
        // Restricted to blob-backed files, matching attachmentsFromPreviousTurns:
        // the tool can only serve what the attachment store holds. An inline or
        // URL-only attachment is already inlined by AttachmentForwarder on this
        // turn, so offering a tool whose listAttachments would report nothing
        // would only contradict the document sitting in the same message.
        int thisTurn = attachmentData == null
                ? 0
                : (int) AttachmentContextExtractor.attachmentsFrom(attachmentData.getResult()).stream()
                        .filter(attachment -> attachment.getStorageRef() != null)
                        .count();
        // Memory-only scan — no attachment-store round trip on turns without files.
        int earlierTurns = AttachmentContextExtractor.attachmentsFromPreviousTurns(memory).size();
        if (thisTurn == 0 && earlierTurns == 0) {
            return;
        }
        var tool = new ReadAttachmentTool(attachmentStore, attachmentTextExtractor, memory.getConversationId());
        tools.add(tool);
        LOGGER.infof(
                "[ATTACHMENTS] ReadAttachmentTool enabled for conversation='%s' (%d this turn, %d from earlier turns)",
                sanitize(memory.getConversationId()), thisTurn, earlierTurns);
    }
}
