/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.docs;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/**
 * Reads EDDI's own markdown documentation off the filesystem.
 * <p>
 * Extracted from {@code McpDocResources} so the same doc set is reachable from
 * more than one surface. MCP <em>resources</em> are only usable by a client
 * that asks for them — EDDI's own MCP client never calls {@code resources/read}
 * — so as long as this logic lived only behind {@code eddi://docs/*}, a desktop
 * MCP client could read EDDI's documentation and an EDDI agent could not. Two
 * surfaces close that gap: REST ({@code /administration/docs}), which an agent
 * generated from EDDI's OpenAPI spec binds as ordinary tools, and the MCP tools
 * {@code list_docs}/{@code read_docs} ({@code McpDocTools}) for agents
 * consuming EDDI's MCP server. All of them — and the resources — delegate here,
 * so {@code eddi.docs.enabled=false} switches every one off together.
 *
 * <h2>Runtime doc set is smaller than the repo's</h2> The Docker image copies
 * only top-level {@code docs/*.md} (non-recursive) and then deletes
 * {@code changelog.md}, {@code code-review-standards.md},
 * {@code incident-response.md} and {@code SUMMARY.md}. So callers must treat
 * "not found" as normal and read {@link #listDocs()} rather than assuming any
 * particular page exists — nothing here should hard-code a doc name.
 */
@ApplicationScoped
public class DocsService {

    private static final Logger LOGGER = Logger.getLogger(DocsService.class);
    private static final String MD_SUFFIX = ".md";

    @ConfigProperty(name = "eddi.docs.path", defaultValue = "docs")
    String docsPath;

    /**
     * Deployment-wide docs kill switch. All four surfaces serving documentation —
     * REST list/read, MCP resources, MCP tools — delegate to this class, so one
     * flag here honestly disables all of them at once. The previous way to achieve
     * this was pointing {@code eddi.docs.path} at a directory that does not exist,
     * which "worked" but read as a misconfiguration in every log line and
     * diagnostic. A policy deserves a switch, not a hack.
     */
    @ConfigProperty(name = "eddi.docs.enabled", defaultValue = "true")
    boolean docsEnabled = true; // initialized so plain construction (tests) matches the config default

    /**
     * The configured docs directory, for diagnostics. Callers surface this when
     * {@link #isAvailable()} is false — "0 documents" reads as success, whereas a
     * mis-set {@code eddi.docs.path} is the realistic misconfiguration and should
     * say so.
     */
    public String docsDirectory() {
        return docsEnabled ? docsPath : docsPath + " (disabled via eddi.docs.enabled=false)";
    }

    /**
     * Whether docs are enabled and the configured directory exists and is a
     * directory.
     */
    public boolean isAvailable() {
        return docsEnabled && Files.isDirectory(Path.of(docsPath));
    }

    /** Sorted names of the available docs, without the {@code .md} suffix. */
    public List<String> listDocs() {
        if (!docsEnabled) {
            return List.of();
        }
        Path docsDir = Path.of(docsPath);
        if (!Files.isDirectory(docsDir)) {
            LOGGER.warnf("Docs directory not found: %s", docsPath);
            return List.of();
        }
        var docs = new TreeSet<String>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(docsDir, "*" + MD_SUFFIX)) {
            for (Path entry : stream) {
                String filename = entry.getFileName().toString();
                docs.add(filename.substring(0, filename.length() - MD_SUFFIX.length()));
            }
        } catch (IOException e) {
            LOGGER.error("Failed to list docs", e);
            return List.of();
        }
        return List.copyOf(docs);
    }

    /**
     * Whether a doc name is usable at all: non-empty and free of path separators
     * and parent references.
     * <p>
     * Public and shared so a caller that wants to tell "you asked for something
     * illegal" apart from "there is no such page" does not have to restate the
     * predicate — {@code McpDocResources} did exactly that, and two copies of a
     * security check drift.
     * <p>
     * Note this is <em>not</em> the only guard: {@link #readDoc} also verifies the
     * resolved path still sits under the docs directory, which catches everything
     * this does and more. The shape check is kept for the clearer rejection it
     * allows, not because the normalisation check needs help.
     */
    public static boolean isValidDocName(String name) {
        return name != null && !name.isEmpty() && !name.contains("/") && !name.contains("\\") && !name.contains("..");
    }

    /**
     * Reads one doc by name (with or without the {@code .md} suffix), or null if
     * the name is unusable or no such doc exists.
     */
    public String readDoc(String name) {
        if (!docsEnabled) {
            // Reads as "no such page" to every surface — deliberately identical to
            // the absent-directory case, so disabling docs does not create a new
            // response shape callers never handled.
            return null;
        }
        if (!isValidDocName(name)) {
            LOGGER.warnf("Rejected doc name: %s", name);
            return null;
        }
        Path docsDir = Path.of(docsPath).toAbsolutePath().normalize();
        Path docFile = docsDir.resolve(name + MD_SUFFIX).normalize();
        if (!docFile.startsWith(docsDir)) {
            LOGGER.warnf("Path traversal attempt blocked: %s", name);
            return null;
        }
        if (!Files.isRegularFile(docFile)) {
            // The name may already carry the .md suffix.
            docFile = docsDir.resolve(name).normalize();
            if (!docFile.startsWith(docsDir) || !Files.isRegularFile(docFile)) {
                return null;
            }
        }
        try {
            return Files.readString(docFile);
        } catch (IOException e) {
            LOGGER.error("Failed to read doc: " + name, e);
            return null;
        }
    }
}
