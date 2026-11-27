/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.FilterContext;
import io.quarkiverse.mcp.server.ToolFilter;
import io.quarkiverse.mcp.server.ToolManager.ToolInfo;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Set;

/**
 * Filters MCP tool visibility to only expose the intended EDDI MCP tools.
 * <p>
 * By default the quarkus-mcp-server extension also picks up
 * {@code dev.langchain4j.agent.tool.Tool} annotations from EDDI's built-in
 * Agent tools (calculator, datetime, websearch, etc.), which are meant ONLY for
 * internal Agent workflow use — not for external MCP clients. That discovery is
 * switched off
 * ({@code quarkus.mcp.server.support-langchain4j-annotations=false}), and this
 * filter stays as the second line of defence should it ever be switched back
 * on.
 * <p>
 * This whitelist ensures only the intended MCP tools are visible.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpToolFilter implements ToolFilter {

    /**
     * Whitelist of MCP tool names that should be exposed to external clients: the
     * key set of {@link McpToolTiers#TIERS}, so a tool is exposed exactly when it
     * has a role tier. All other tools (built-in langchain4j Agent tools) are
     * hidden.
     * <p>
     * <b>A tool missing from that map is invisible, silently.</b>
     * {@code ToolFilter} sees only a name, so a newly added {@code @Tool} that
     * nobody adds to the map still compiles and never appears in {@code tools/list}
     * (its own role gate would also fail on the missing tier).
     * {@code McpToolFilterCoverageTest} pins both directions — every {@code @Tool}
     * is listed, and every listed name exists — so that failure mode cannot recur.
     * Package-visible for exactly that test.
     */
    static final Set<String> MCP_TOOLS = McpToolTiers.TIERS.keySet();

    @Override
    public boolean test(ToolInfo toolInfo, FilterContext filterContext) {
        return MCP_TOOLS.contains(toolInfo.name());
    }
}
