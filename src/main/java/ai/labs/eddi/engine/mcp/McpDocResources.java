package ai.labs.eddi.engine.mcp;

import io.quarkiverse.mcp.server.Resource;
import io.quarkiverse.mcp.server.ResourceTemplate;
import io.quarkiverse.mcp.server.ResourceTemplateArg;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Expose EDDI documentation as MCP resources.
 * <p>
 * AI agents can browse and read the 40+ markdown docs via
 * MCP resources/list and resources/read.
 * <p>
 * Docs are loaded from the filesystem at a configurable path
 * (default: {@code docs/} relative to working directory).
 * In Docker, docs must be copied into the container.
 *
 * @author ginccc
 */
@ApplicationScoped
public class McpDocResources {

    private static final Logger LOGGER = Logger.getLogger(McpDocResources.class);

    @ConfigProperty(name = "eddi.docs.path", defaultValue = "docs")
    String docsPath;

    /**
     * Read a specific doc by name.
     * Example URI: eddi://docs/getting-started
     *
     * @param name the doc filename without .md extension
     * @return the markdown content of the doc
     */
    @ResourceTemplate(uriTemplate = "eddi://docs/{name}",
            name = "eddi-doc",
            description = "Read an EDDI documentation page by name. " +
                    "Pass the doc name without .md extension, " +
                    "e.g. 'getting-started', 'architecture', 'langchain'")
    public String readDoc(@ResourceTemplateArg(name = "name") String name) {
        Path docFile = Path.of(docsPath, name + ".md");
        if (!Files.isRegularFile(docFile)) {
            // Try without adding .md in case the name already has it
            docFile = Path.of(docsPath, name);
            if (!Files.isRegularFile(docFile)) {
                return "Document not found: " + name;
            }
        }
        try {
            return Files.readString(docFile);
        } catch (IOException e) {
            LOGGER.error("Failed to read doc: " + name, e);
            return "Error reading document: " + e.getMessage();
        }
    }

    /**
     * List all available documentation pages.
     * This is exposed as a static resource at eddi://docs/index.
     */
    @Resource(uri = "eddi://docs/index",
            name = "eddi-docs-index",
            description = "List of all available EDDI documentation pages")
    public String listDocs() {
        Path docsDir = Path.of(docsPath);
        if (!Files.isDirectory(docsDir)) {
            return "Docs directory not found: " + docsPath;
        }

        var docs = new TreeSet<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(docsDir, "*.md")) {
            for (Path entry : stream) {
                String filename = entry.getFileName().toString();
                docs.add(filename.substring(0, filename.length() - 3)); // remove .md
            }
        } catch (IOException e) {
            LOGGER.error("Failed to list docs", e);
            return "Error listing documents: " + e.getMessage();
        }

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
