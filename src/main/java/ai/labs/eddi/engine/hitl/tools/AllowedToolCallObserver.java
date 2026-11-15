/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import ai.labs.eddi.configs.hitl.model.ToolApprovalsConfig;
import ai.labs.eddi.engine.memory.IConversationMemory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Told about every tool call the approval gate let through, right after it
 * executed — the hook the sampled audit uses. Called on the turn's thread, so
 * an implementation must be quick and must never throw; the default is
 * {@link #NONE}.
 *
 * @since 6.7.0
 */
@FunctionalInterface
public interface AllowedToolCallObserver {

    /** Observes nothing. */
    AllowedToolCallObserver NONE = (memory, request, toolSources, toolEndpoints, effectiveToolApprovals, trace) -> {
    };

    /**
     * One allowed call has executed.
     *
     * @param toolSources
     *            tool name to source ({@code http}, {@code mcp}, …)
     * @param toolEndpoints
     *            tool name to {@code method:path} for http tools
     * @param effectiveToolApprovals
     *            the config the gate classified the call with, or null
     * @param trace
     *            the turn's tool trace, the call's own entries last
     */
    void afterAllowed(IConversationMemory memory, ToolExecutionRequest request, Map<String, String> toolSources,
                      Map<String, String> toolEndpoints, ToolApprovalsConfig effectiveToolApprovals, List<Map<String, Object>> trace);

    /**
     * An observer resolved when it is first used — for an owner whose real observer
     * is field-injected after the runner is built. Swallows anything the delegate
     * throws.
     */
    static AllowedToolCallObserver deferred(Supplier<? extends AllowedToolCallObserver> delegate) {
        return (memory, request, toolSources, toolEndpoints, effectiveToolApprovals, trace) -> {
            try {
                AllowedToolCallObserver observer = delegate.get();
                if (observer != null) {
                    observer.afterAllowed(memory, request, toolSources, toolEndpoints, effectiveToolApprovals, trace);
                }
            } catch (RuntimeException e) {
                Logger.getLogger(AllowedToolCallObserver.class).debugf("Allowed-call observer failed: %s", e.getMessage());
            }
        };
    }
}
