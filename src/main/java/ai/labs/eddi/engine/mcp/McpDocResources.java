/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.docs.DocsService;
import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.ResourceTemplateArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Expose EDDI documentation as MCP resources.
 * <p>
 * AI agents can browse and read the 40+ markdown docs via MCP resources/list
 * and resources/read.
 * <p>
 * Thin delegate over {@link DocsService}, which owns the filesystem access and
 * the path-traversal guard. The same doc set is served over REST at
 * {@code /administration/docs} — MCP <em>resources</em> only reach clients that
 * ask for them, and EDDI's own MCP client never does, so this surface alone
 * left EDDI's docs readable by a desktop client and not by an EDDI agent.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpDocResources {

    private final DocsService docsService;

    @Inject
    public McpDocResources(DocsService docsService) {
        this.docsService = docsService;
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
        if (!docsService.isAvailable()) {
            return "Docs directory not found: " + docsService.docsDirectory();
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
