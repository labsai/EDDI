/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.docs.DocsService;
import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.ResourceTemplateArg;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;

import static ai.labs.eddi.engine.mcp.McpToolUtils.requireAnyRole;

/**
 * Expose EDDI documentation as MCP resources.
 * <p>
 * AI agents can browse and read the 40+ markdown docs via MCP resources/list
 * and resources/read.
 * <p>
 * Thin delegate over {@link DocsService}, which owns the filesystem access, the
 * path-traversal guard, and the {@code eddi.docs.enabled} switch. The same doc
 * set is served over REST at {@code /administration/docs} and as MCP
 * <em>tools</em> ({@link McpDocTools}) — resources only reach clients that ask
 * for them, and agentic MCP clients (EDDI's own included) never do, so this
 * surface alone reached desktop clients and no agent.
 * <p>
 * Guarded by the same role set as {@link McpDocTools} and {@code IRestDocs}
 * ({@link McpRoles#DOCS}): the pages are the same, so the door must be too. A
 * resource has no {@code isError} result, so a refusal is a JSON-RPC error with
 * {@link JsonRpcErrorCodes#SECURITY_ERROR}.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpDocResources {

    private final DocsService docsService;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpDocResources(DocsService docsService, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.docsService = docsService;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    private void requireDocsRole() {
        try {
            requireAnyRole(identity, authEnabled, McpRoles.DOCS);
        } catch (ForbiddenException e) {
            throw new McpException(e.getMessage(), JsonRpcErrorCodes.SECURITY_ERROR);
        }
    }

    /**
     * Read a specific doc by name. Example URI: eddi://docs/getting-started
     *
     * @param name
     *            the doc filename without .md extension
     * @return the markdown content of the doc
     */
    @ResourceTemplate(uriTemplate = "eddi://docs/{name}", name = "eddi-doc", description = "Read an EDDI documentation page by name. "
            + "Pass the doc name without .md extension, " + "e.g. 'getting-started', 'architecture', 'langchain'")
    public String readDoc(@ResourceTemplateArg(name = "name") String name) {
        requireDocsRole();
        String content = docsService.readDoc(name);
        if (content != null) {
            return content;
        }
        // MCP resources have no error channel here, so the two failure modes stay
        // distinguishable in the returned text, exactly as before the extraction:
        // a rejected name reads as invalid, an accepted-but-absent one as not found.
        // The predicate comes from DocsService rather than being restated here — two
        // copies of a security check drift, and this one decides which message a
        // traversal attempt gets.
        if (!DocsService.isValidDocName(name)) {
            return "Invalid document name: " + name;
        }
        return "Document not found: " + name;
    }

    /**
     * List all available documentation pages. This is exposed as a static resource
     * at eddi://docs/index.
     */
    @Resource(uri = "eddi://docs/index", name = "eddi-docs-index", description = "List of all available EDDI documentation pages")
    public String listDocs() {
        requireDocsRole();
        if (!docsService.isAvailable()) {
            // The server-side directory is deliberately not named: it is deployment
            // layout, not something a reader of the index can act on.
            return "No documentation is available on this deployment.";
        }
        List<String> docs = docsService.listDocs();
        var sb = new StringBuilder();
        sb.append("# EDDI Documentation Index\n\n");
        sb.append("Available documents (").append(docs.size()).append("):\n\n");
        for (String doc : docs) {
            sb.append("- ").append(doc).append("\n");
        }
        sb.append("\nUse eddi://docs/{name} to read a specific document.");
        return sb.toString();
    }
}
