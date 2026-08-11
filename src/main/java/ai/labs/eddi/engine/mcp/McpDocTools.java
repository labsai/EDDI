/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.docs.DocsService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;

/**
 * MCP <em>tools</em> for reading EDDI's own documentation — the counterpart to
 * {@link McpDocResources}, which exposes the same pages as MCP resources.
 * <p>
 * Both exist because the two surfaces reach different consumers. Resources only
 * reach a client that calls {@code resources/read} — desktop MCP clients do,
 * but agentic MCP clients (EDDI's own {@code McpToolProviderManager} included)
 * consume {@code tools/list} and never touch resources. Until these tools
 * existed, {@code docs/mcp-server.md} documented a
 * {@code toolsWhitelist: ["read_docs", "list_docs"]} example that matched
 * nothing, silently producing a tool-less server. These tools make that
 * configuration real: any agent consuming EDDI's MCP server — another EDDI
 * instance via an {@code mcpcalls} config, or any third-party agent — can now
 * read the docs.
 * <p>
 * Role set mirrors {@code IRestDocs} exactly (all five roles, enumerated — EDDI
 * has no role hierarchy): these are published documentation pages, so the
 * widest read tier applies, and the two surfaces must agree. Both delegate to
 * {@link DocsService}, which owns the filesystem access, the path-traversal
 * guard, and the {@code eddi.docs.enabled} switch.
 *
 * @author ginccc
 * @since 6.3.0
 */
@ApplicationScoped
public class McpDocTools {

    /**
     * Immutable, and a {@code List} rather than an array: a {@code static final}
     * array is still element-mutable, which is both a static-analysis finding and a
     * real hazard for a constant that decides an authorization check.
     */
    private static final List<String> DOC_READ_ROLES = List.of("eddi-admin", "eddi-editor", "eddi-user", "eddi-approver", "eddi-viewer");

    private final DocsService docsService;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpDocTools(DocsService docsService,
            SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled",
                            defaultValue = "false") boolean authEnabled) {
        this.docsService = docsService;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "list_docs", description = "List the names of EDDI's own documentation pages available on this "
            + "deployment, one per line, without the .md suffix. The runtime set is smaller than the repository's — "
            + "read this list rather than assuming a page exists, then read pages with read_docs.")
    public String listDocs() {
        requireAnyRole(identity, authEnabled, DOC_READ_ROLES);
        if (!docsService.isAvailable()) {
            // Not an error to the model: an absent or disabled docs directory is a
            // legitimate deployment state, and the message says which it is.
            return "No documentation is available on this deployment (docs directory: "
                    + docsService.docsDirectory() + ").";
        }
        List<String> docs = docsService.listDocs();
        if (docs.isEmpty()) {
            return "No documentation pages found (docs directory: " + docsService.docsDirectory() + ").";
        }
        return String.join("\n", docs);
    }

    @Tool(name = "read_docs", description = "Read one of EDDI's own documentation pages as markdown. Pass the page "
            + "name without the .md suffix, e.g. 'getting-started', 'architecture', 'hitl'. Use list_docs first — "
            + "not every page in the public repository ships on every deployment.")
    public String readDocs(
                           @ToolArg(description = "Page name without the .md suffix (required)") String name) {
        requireAnyRole(identity, authEnabled, DOC_READ_ROLES);
        String content = docsService.readDoc(name);
        if (content != null) {
            return content;
        }
        // Same two distinguishable failure texts as McpDocResources: MCP tools have
        // no typed error channel worth using for a lookup miss, and the predicate
        // stays in DocsService so the security check exists exactly once.
        if (!DocsService.isValidDocName(name)) {
            return "Invalid document name: " + name;
        }
        return "Document not found: " + name + ". Call list_docs for the pages this deployment actually serves.";
    }
}
