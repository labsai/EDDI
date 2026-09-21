/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.apicalls.impl.ResolvedRequest;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Resolves what a tool would send, without sending it.
 * <p>
 * Only httpcall tools have one. A builtin, MCP or A2A tool is not an HTTP
 * request this side of the boundary, so there is nothing to pin — those calls
 * pause and are approved on their name and arguments alone, as before request
 * pinning existed. {@code public} (unlike most SPI-adjacent types, which stay
 * package-private) because it is referenced from three packages: the provider
 * that produces it ({@code HttpCallToolsProvider}, {@code impl}), the assembled
 * {@link ToolContribution} that carries it ({@code tools.spi}), and the
 * pause/resume machinery that consumes it
 * ({@code impl.orchestration.ToolApprovalGateSupport},
 * {@code ToolLoopResumer}).
 */
@FunctionalInterface
public interface ToolRequestResolver {
    ResolvedRequest resolve(ToolExecutionRequest toolRequest) throws LifecycleException;
}
