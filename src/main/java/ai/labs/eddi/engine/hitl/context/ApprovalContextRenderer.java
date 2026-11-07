/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.context;

import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateEngineException;
import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.Settings;
import ai.labs.eddi.modules.templating.impl.RuntimeTemplateEngineFactory.TemplateLimitExceededException;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.qute.Engine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Renders the designer-authored {@link ApprovalContextBlock}s of a pause
 * <b>once, when the pause is committed</b>, into the
 * {@link RenderedApprovalContext} that every surface then shows unchanged.
 * <p>
 * The blocks are author-written templates; everything they read — properties,
 * tool arguments, model output, group transcripts — is <em>data</em>
 * substituted into them, never a template string itself (see
 * {@code docs/security.md#runtime-template-engine}). They run on a dedicated
 * {@link TemplatingEngine} built from the restricted runtime engine with tight
 * limits (output, loop iterations, wall clock), so a block cannot stall the
 * pause.
 * <p>
 * Pipeline per block: render (TEXT) → mask reference tokens ({@code ${vault:…}}
 * and kin) as {@code [secret]} → {@link SecretRedactionFilter} → trim → drop
 * when blank → cut to the block's {@code maxChars} → stop at the total cap.
 * <b>A render that fails or times out becomes an item with an {@code error} and
 * never throws:</b> the pause is committed whatever the context did.
 * <p>
 * Metric {@code eddi.hitl.approval_context.render} counts blocks by
 * {@code outcome} ({@code ok}, {@code empty}, {@code failed},
 * {@code too_large}) and {@code surface} ({@code regular}, {@code group}).
 */
@ApplicationScoped
public class ApprovalContextRenderer {

    private static final Logger LOGGER = Logger.getLogger(ApprovalContextRenderer.class);

    /** Default for {@code eddi.hitl.approval-context.max-total-chars}. */
    public static final int DEFAULT_MAX_TOTAL_CHARS = 8_000;
    /** Default for {@code eddi.hitl.approval-context.render-timeout-ms}. */
    public static final long DEFAULT_RENDER_TIMEOUT_MS = 500;
    /** Output cap of one block's render (before {@code maxChars} cuts it). */
    static final int MAX_RENDER_OUTPUT_CHARS = 16_000;
    /** Loop-iteration cap of one block's render. */
    static final int MAX_RENDER_ITERATIONS = 2_000;

    static final String METRIC = "eddi.hitl.approval_context.render";
    public static final String SURFACE_REGULAR = "regular";
    public static final String SURFACE_GROUP = "group";

    static final String ELLIPSIS = "…";
    /** Reference tokens a template can pass through verbatim. */
    private static final Pattern REFERENCE_TOKEN = Pattern.compile("\\$\\{\\s*(?:vault|eddivault|connection|caller)\\s*:[^}]*\\}",
            Pattern.CASE_INSENSITIVE);
    static final String MASK = "[secret]";

    private final ITemplatingEngine templatingEngine;
    private final IMemoryItemConverter memoryItemConverter;
    private final MeterRegistry meterRegistry;
    private final int maxTotalChars;

    @Inject
    public ApprovalContextRenderer(Engine quarkusEngine, IMemoryItemConverter memoryItemConverter, MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.hitl.approval-context.max-total-chars", defaultValue = "8000") int maxTotalChars,
            @ConfigProperty(name = "eddi.hitl.approval-context.render-timeout-ms", defaultValue = "500") long renderTimeoutMs,
            @ConfigProperty(name = "quarkus.qute.strict-rendering", defaultValue = "false") boolean strictRendering,
            @ConfigProperty(name = "quarkus.qute.remove-standalone-lines", defaultValue = "true") boolean removeStandaloneLines,
            @ConfigProperty(name = "quarkus.qute.iteration-metadata-prefix", defaultValue = "<alias_>") String iterationMetadataPrefix) {
        this(new TemplatingEngine(quarkusEngine, new Settings(MAX_RENDER_OUTPUT_CHARS, MAX_RENDER_ITERATIONS, strictRendering,
                removeStandaloneLines, iterationMetadataPrefix, renderTimeoutMs)), memoryItemConverter, meterRegistry, maxTotalChars);
    }

    /** For tests and callers that supply the templating engine. */
    public ApprovalContextRenderer(ITemplatingEngine templatingEngine, IMemoryItemConverter memoryItemConverter, MeterRegistry meterRegistry,
            int maxTotalChars) {
        this.templatingEngine = templatingEngine;
        this.memoryItemConverter = memoryItemConverter;
        this.meterRegistry = meterRegistry;
        this.maxTotalChars = maxTotalChars;
    }

    // ------------------------------------------------------------------ regular
    // conversations

    /**
     * Renders the context of the pause {@code memory} is in: the agent-level
     * {@code approvalContext} blocks and, on a tool pause, the {@code context}
     * blocks of the {@code toolApprovals} rule each gated call matched.
     *
     * @param agentBlocks
     *            {@code hitlConfig.approvalContext}; may be null
     * @param fallbackToolApprovals
     *            the agent-level {@code toolApprovals}, used when the batch does
     *            not carry the config that gated it; may be null
     * @return the context, or {@code null} when no block applies (nothing
     *         configured), every block rendered blank, or the rendering failed as a
     *         whole — never throws
     */
    public RenderedApprovalContext renderForConversation(IConversationMemory memory, List<ApprovalContextBlock> agentBlocks,
                                                         ToolApprovalsConfig fallbackToolApprovals) {
        try {
            List<Work> work = new ArrayList<>();
            PendingToolCallBatch batch = memory.getHitlPendingToolCalls();
            List<PendingToolCall> calls = batch != null && batch.getCalls() != null ? batch.getCalls() : List.of();
            ToolApprovalsConfig toolApprovals = batch != null && batch.getEffectiveToolApprovals() != null
                    ? batch.getEffectiveToolApprovals()
                    : fallbackToolApprovals;
            boolean anyRuleBlock = false;
            if (toolApprovals != null && toolApprovals.getRules() != null) {
                for (PendingToolCall call : calls) {
                    var rule = ruleOf(toolApprovals, call);
                    if (rule != null && rule.getContext() != null && !rule.getContext().isEmpty()) {
                        anyRuleBlock = true;
                        break;
                    }
                }
            }
            boolean anyAgentBlock = agentBlocks != null && !agentBlocks.isEmpty();
            if (!anyAgentBlock && !anyRuleBlock) {
                return null;
            }

            Map<String, Object> data = new LinkedHashMap<>(memoryItemConverter.convert(memory));
            data.put("hitl", ApprovalContextTemplateData.hitl(memory));

            if (anyAgentBlock) {
                for (ApprovalContextBlock block : agentBlocks) {
                    work.add(new Work(block, block.getLabel(), RenderedApprovalContext.SOURCE_AGENT, null, data));
                }
            }
            if (anyRuleBlock) {
                int rendered = 0;
                for (PendingToolCall call : calls) {
                    if (rendered++ >= ApprovalContextTemplateData.MAX_CALLS) {
                        break;
                    }
                    var rule = ruleOf(toolApprovals, call);
                    if (rule == null || rule.getContext() == null) {
                        continue;
                    }
                    Map<String, Object> callData = new LinkedHashMap<>(data);
                    callData.put("call", ApprovalContextTemplateData.call(call));
                    String suffix = calls.size() > 1 && call.getToolName() != null ? " — " + call.getToolName() : "";
                    for (ApprovalContextBlock block : rule.getContext()) {
                        work.add(new Work(block, block.getLabel() + suffix, RenderedApprovalContext.SOURCE_RULE_PREFIX + rule.getMatch(),
                                call.getCallId(), callData));
                    }
                }
            }
            return renderAll(work, SURFACE_REGULAR);
        } catch (RuntimeException | StackOverflowError e) {
            countOutcome("failed", SURFACE_REGULAR);
            LOGGER.warnf("Approval context not rendered for %s: %s", sanitize(memory.getConversationId()), sanitize(e.getMessage()));
            return null;
        }
    }

    private static ToolApprovalsConfig.ApprovalRule ruleOf(ToolApprovalsConfig toolApprovals, PendingToolCall call) {
        if (toolApprovals == null || toolApprovals.getRules() == null || call.getMatchedRule() == null) {
            return null;
        }
        for (ToolApprovalsConfig.ApprovalRule rule : toolApprovals.getRules()) {
            if (rule != null && call.getMatchedRule().equals(rule.getMatch())) {
                return rule;
            }
        }
        return null;
    }

    /**
     * Adds {@code items} (for instance a guardrail finding, {@code source:
     * "guardrail"}) to the context already on {@code memory}; nothing is rendered.
     * Skipped when {@code items} is empty.
     */
    public void appendItems(IConversationMemory memory, List<Item> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        RenderedApprovalContext existing = memory.getHitlApprovalContext();
        List<Item> merged = new ArrayList<>();
        boolean truncated = false;
        if (existing != null) {
            merged.addAll(existing.getItems());
            truncated = existing.isTruncated();
        }
        merged.addAll(items);
        memory.setHitlApprovalContext(RenderedApprovalContext.of(merged, truncated));
    }

    // ------------------------------------------------------------------ shared
    // core

    /** One block to render, with the data it reads. */
    public record Work(ApprovalContextBlock block, String label, String source, String callId, Map<String, Object> data) {
    }

    /**
     * Renders {@code work} in order under the total cap. Public for the group
     * renderer, which builds its own data.
     *
     * @return the context, or {@code null} when {@code work} is empty
     */
    public RenderedApprovalContext renderAll(List<Work> work, String surface) {
        if (work == null || work.isEmpty()) {
            return null;
        }
        List<Item> items = new ArrayList<>();
        boolean truncated = false;
        int used = 0;
        for (Work w : work) {
            ApprovalContextBlock block = w.block();
            if (block == null || block.getTemplate() == null || block.getTemplate().isBlank()) {
                continue;
            }
            int remaining = maxTotalChars - used;
            if (remaining <= 0) {
                truncated = true;
                break;
            }
            String label = labelOf(w.label());
            String text;
            try {
                text = templatingEngine.processTemplate(block.getTemplate(), w.data(), TemplateMode.TEXT);
            } catch (TemplateEngineException | RuntimeException | StackOverflowError e) {
                String error = isTooLarge(e) ? RenderedApprovalContext.ERROR_TOO_LARGE : RenderedApprovalContext.ERROR_RENDER_FAILED;
                countOutcome(RenderedApprovalContext.ERROR_TOO_LARGE.equals(error) ? "too_large" : "failed", surface);
                LOGGER.warnf("Approval context block '%s' not rendered (%s): %s", sanitize(label), error, sanitize(e.getMessage()));
                items.add(new Item(label, "", false, w.source(), w.callId(), error));
                continue;
            }
            text = clean(text);
            if (text.isEmpty()) {
                countOutcome("empty", surface);
                continue;
            }
            int limit = Math.min(block.effectiveMaxChars(), remaining);
            boolean cut = false;
            if (text.length() > limit) {
                text = cutTo(text, limit);
                cut = true;
                if (limit == remaining && remaining < block.effectiveMaxChars()) {
                    truncated = true;
                }
            }
            countOutcome("ok", surface);
            items.add(new Item(label, text, cut, w.source(), w.callId(), null));
            used += text.length();
        }
        if (items.isEmpty()) {
            return null;
        }
        return RenderedApprovalContext.of(items, truncated);
    }

    private static String labelOf(String label) {
        String value = SecretRedactionFilter.redact(label == null ? "" : label.strip());
        return value.length() > 120 ? value.substring(0, 120) : value;
    }

    /** Masks reference tokens, redacts secrets and trims. */
    static String clean(String rendered) {
        if (rendered == null) {
            return "";
        }
        String masked = REFERENCE_TOKEN.matcher(rendered).replaceAll(MASK);
        return SecretRedactionFilter.redact(masked).strip();
    }

    /**
     * {@code text} cut to at most {@code limit} characters including the ellipsis,
     * never inside a surrogate pair.
     */
    static String cutTo(String text, int limit) {
        int end = Math.max(0, limit - ELLIPSIS.length());
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end).stripTrailing() + ELLIPSIS;
    }

    private static boolean isTooLarge(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TemplateLimitExceededException) {
                return true;
            }
        }
        return false;
    }

    private void countOutcome(String outcome, String surface) {
        try {
            meterRegistry.counter(METRIC, "outcome", outcome, "surface", surface).increment();
        } catch (RuntimeException e) {
            LOGGER.debugf("approval context metric not recorded: %s", e.getMessage());
        }
    }
}
