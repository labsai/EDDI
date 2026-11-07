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
 * Only tools that call out to a remote endpoint have one: httpcall tools (the
 * resolved HTTP request) and MCP and A2A tools (a preview of the JSON-RPC call,
 * built by {@code RemoteToolRequestResolvers}). A built-in tool is not a
 * request this side of the boundary, so there is nothing to pin — those calls
 * pause and are approved on their name and arguments alone. {@code public}
 * (unlike most SPI-adjacent types, which stay package-private) because it is
 * referenced from three packages: the providers that produce it
 * ({@code HttpCallToolsProvider}, {@code McpToolsProvider},
 * {@code A2AToolsProvider}, in {@code impl}), the assembled
 * {@link ToolContribution} that carries it ({@code tools.spi}), and the
 * pause/resume machinery that consumes it
 * ({@code impl.orchestration.ToolApprovalGateSupport},
 * {@code ToolLoopResumer}).
 */
@FunctionalInterface
public interface ToolRequestResolver {
    ResolvedRequest resolve(ToolExecutionRequest toolRequest) throws LifecycleException;
}
