/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.tools.spi.ToolContribution;
import ai.labs.eddi.modules.llm.tools.spi.ToolSourceProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code readAttachment} — the one local tool source no built-in gate governs
 * (R2 step 2, the plan's {@code AttachmentToolProvider}).
 * <p>
 * Split out of {@link ContextualToolsProvider} at the rewiring step for a
 * concrete reason, not tidiness. The pre-SPI {@code collectAllBuiltInTools}
 * added {@code readAttachment} <em>after</em> the dynamic-agent tools in its
 * whitelist branch, and spec order is the order the model sees its tools in.
 * Leaving it inside the contextual provider would have moved it ahead of the
 * dynamic block for any agent that has both a dynamic-agent whitelist and
 * attachments in the conversation — a small, silent presentation change, and
 * exactly the kind a "pure move" is not allowed to make. Its own provider,
 * assembled last, reproduces the old order exactly.
 * <p>
 * That it also reads better this way is a bonus: unlike user memory and
 * conversation recall, this tool is deliberately outside {@code
 * enableBuiltInTools} and the whitelist entirely (it is part of attachment
 * support, not a configurable capability — see
 * {@code ContextualToolsProvider#addReadAttachmentToolIfEnabled} for why gating
 * it would be actively wrong), so grouping it with two whitelist-gated tools
 * was always slightly false.
 * <p>
 * Delegates the actual construction back to {@link ContextualToolsProvider}
 * rather than duplicating it: the enablement rule (blob-backed attachments on
 * this or any earlier turn) is subtle and belongs in exactly one place.
 * Constructed fresh per call for the same reason that class is — its stores are
 * field-injected on {@code AgentOrchestrator} and null at construction time.
 */
class AttachmentToolsProvider implements ToolSourceProvider {

    private final ContextualToolsProvider contextualToolsProvider;

    AttachmentToolsProvider(ContextualToolsProvider contextualToolsProvider) {
        this.contextualToolsProvider = contextualToolsProvider;
    }

    @Override
    public String source() {
        return "builtin";
    }

    @Override
    public ToolContribution contribute(ToolAssemblyContext ctx) {
        List<Object> tools = new ArrayList<>();
        contextualToolsProvider.addReadAttachmentToolIfEnabled(tools, ctx.memory());
        if (tools.isEmpty()) {
            return ToolContribution.empty();
        }
        var reflected = ToolObjectReflector.reflect(tools);
        return new ToolContribution(reflected.specs(), reflected.executors(), reflected.toolSources(), Map.of(),
                List.of(), reflected.toolCanonicalNames());
    }
}
