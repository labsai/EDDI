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
import ai.labs.eddi.engine.model.Context;
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
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collections;

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

    /**
     * Agents already warned about the memory/built-ins conflict — see
     * {@link #warnIfMemoryEnabledButBuiltInsAreOff}. Static because
     * {@code AgentOrchestrator} constructs this provider per call, so an instance
     * field would debounce nothing. Bounded and expiring rather than a plain set:
     * "the number of distinct agents" is not a fixed bound on this platform —
     * dynamic and ephemeral agents are created at runtime with fresh ids, so a
     * long-lived deployment with churn would otherwise accumulate entries for the
     * JVM lifetime. Expiry means a standing misconfiguration re-announces itself
     * periodically instead of exactly once per process; suppression is therefore
     * best-effort, holding only while the entry remains cached.
     */
    private static final Set<String> MEMORY_MISCONFIGURATION_WARNED = Collections.newSetFromMap(
            Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofHours(24))
                    .<String, Boolean>build().asMap());

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
     * escalation, not a cosmetic difference. An agent that enabled memory and lands
     * here is warned about rather than skipped silently — see
     * {@link #warnIfMemoryEnabledButBuiltInsAreOff}.</li>
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
        } else {
            warnIfMemoryEnabledButBuiltInsAreOff(ctx);
        }
        // readAttachment is NOT contributed here — see AttachmentToolsProvider. It
        // has to be assembled after the dynamic-agent tools to keep the pre-SPI spec
        // order, and it is the one tool in this cluster that no built-in gate
        // governs.
        if (tools.isEmpty()) {
            return ToolContribution.empty();
        }
        var reflected = ToolObjectReflector.reflect(tools);
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of(),
                List.of(), reflected.toolCanonicalNames());
    }

    /**
     * Says out loud that an agent asked for persistent memory and this task will
     * not give it any.
     * <p>
     * Attaching {@code UserMemoryTool} is a three-way conjunction across two
     * configuration files — the agent's {@code enableMemoryTools}, its
     * {@code userMemoryConfig}, and this task's {@code enableBuiltInTools} — and
     * failing any part produced no output at all. An agent designer who enabled
     * memory and got none had nothing to read: no error, no log line, and a model
     * that cheerfully claimed to have saved things it had not. The conflict is
     * resolved the restrictive way on purpose ({@code enableBuiltInTools: false} is
     * a task-level statement that this task gets no built-in capability, and user
     * memory is a cross-conversation write), but it must be visible.
     */
    /**
     * Whether the memory-misconfiguration warning should be emitted for this agent.
     * <p>
     * A missing agent id maps to a stable fallback key rather than bypassing
     * deduplication — the bypass meant the defensive null path was the one case
     * that logged on every turn, the exact flood the cache exists to prevent.
     * Suppression holds while the cache entry remains present (size- and
     * TTL-bounded), so a repeat is possible after expiry or under heavy churn; that
     * is the right trade for a log-hygiene cache, and it means a standing
     * misconfiguration re-announces itself rather than going silent forever.
     */
    static boolean shouldWarnAboutMemoryMisconfiguration(String agentId) {
        return MEMORY_MISCONFIGURATION_WARNED.add(agentId != null ? agentId : "<no-agent-id>");
    }

    private void warnIfMemoryEnabledButBuiltInsAreOff(ToolAssemblyContext ctx) {
        if (ctx.memory().getUserMemoryConfig() == null || userMemoryStore == null) {
            return;
        }
        // Suppressed per agent, not per turn: this fires on every turn of a
        // misconfigured agent, and a busy production agent would otherwise flood the
        // log with the same sentence thousands of times — which trains operators to
        // ignore it.
        String agentId = ctx.memory().getAgentId();
        if (!shouldWarnAboutMemoryMisconfiguration(agentId)) {
            return;
        }
        LOGGER.warnf("[MEMORY] Agent '%s' has persistent user memory enabled, but this LLM task has "
                + "enableBuiltInTools=%s — UserMemoryTool is NOT attached and the agent cannot write memories. "
                + "Set enableBuiltInTools: true on the task (conversation='%s'). Repeats of this warning are suppressed.",
                sanitize(agentId), ctx.task().getEnableBuiltInTools(),
                sanitize(ctx.memory().getConversationId()));
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

        List<String> groupIds = resolveGroupIds(memory);

        var tool = new UserMemoryTool(userMemoryStore, memory.getUserId(), memory.getAgentId(), memory.getConversationId(), groupIds, config);
        tools.add(tool);
        // Conversation id, not user id: sanitize() strips control characters, it does
        // not make an identifier non-personal, and this line fires on every turn that
        // enables the tool. The conversation id resolves back to the user through the
        // store when an operator genuinely needs that, without writing a stable
        // per-person identifier into logs that outlive the conversation.
        LOGGER.infof("[MEMORY] UserMemoryTool enabled for agent='%s', conversation='%s', groups=%s",
                sanitize(memory.getAgentId()), sanitize(memory.getConversationId()),
                groupIds.stream().map(g -> sanitize(g)).toList());
    }

    /**
     * The group(s) whose shared memories this turn may see and write, for
     * {@link UserMemoryTool}'s {@code group} visibility scope.
     * <p>
     * {@code groupId} arrives as a <b>context</b> value —
     * {@code MemberTurnExecutor} and {@code GroupLifecycleOps} both inject it that
     * way, and nothing anywhere writes it as a conversation <em>property</em>. This
     * method previously read only the property, so {@code groupIds} was empty on
     * every group member turn and the tool silently fell back to self-scope:
     * group-visible memories were loaded at conversation init (via
     * {@code Conversation.extractGroupIds}, which does read the context) but could
     * never be recalled or written by the tool mid-conversation. Raised by Copilot
     * on PR #626; the defect predates this branch — R2a moved it verbatim out of
     * {@code AgentOrchestrator}.
     * <p>
     * Reads {@code context:groupId} the way {@code DynamicAgentToolsProvider}
     * resolves its own delegation-depth context, falling back to the current step
     * and then to any earlier step, since a resumed turn re-enters without the
     * original context map. The property read is kept as a last resort so a config
     * that genuinely does set a {@code groupId} property still works.
     */
    static List<String> resolveGroupIds(IConversationMemory memory) {
        String contextKey = "context:groupId";

        var currentStep = memory.getCurrentStep();
        if (currentStep != null) {
            String fromCurrent = contextValueAsString(currentStep.getLatestData(contextKey));
            if (fromCurrent != null) {
                return List.of(fromCurrent);
            }
        }

        var allSteps = memory.getAllSteps();
        if (allSteps != null) {
            List<IData<Object>> priorEntries = allSteps.getAllLatestData(contextKey);
            if (priorEntries != null) {
                for (IData<Object> entry : priorEntries) {
                    String value = contextValueAsString(entry);
                    if (value != null) {
                        return List.of(value);
                    }
                }
            }
        }

        var props = memory.getConversationProperties();
        if (props != null && props.get("groupId") instanceof Property p && p.getValueString() != null) {
            return List.of(p.getValueString());
        }
        return List.of();
    }

    /** Unwraps a {@code context:*} data entry, which holds a {@link Context}. */
    private static String contextValueAsString(IData<?> data) {
        if (data == null || data.getResult() == null) {
            return null;
        }
        Object result = data.getResult();
        Object value = result instanceof Context ctx ? ctx.getValue() : result;
        if (value == null) {
            return null;
        }
        String asString = String.valueOf(value);
        return asString.isBlank() ? null : asString;
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
