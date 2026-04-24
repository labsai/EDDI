/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.utils.RestUtilities;
import ai.labs.eddi.utils.FileUtilities;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

import static ai.labs.eddi.backup.impl.AbstractBackupService.*;

/**
 * Reads agent resource data from an unzipped directory (the result of unzipping
 * an EDDI agent export ZIP). Produces {@link IResourceSource} data records that
 * can be fed to {@link StructuralMatcher} for preview and to
 * {@link UpgradeExecutor} for content sync.
 * <p>
 * This class is NOT a CDI bean — it's constructed per-import with the path to
 * the unzipped directory. Implements {@link AutoCloseable} to allow cleanup of
 * temporary files.
 *
 * <h3>Expected ZIP directory structure</h3>
 *
 * <pre>
 * &lt;rootDir&gt;/
 *   &lt;agentId&gt;/
 *     &lt;agentId&gt;.agent.json
 *     &lt;agentId&gt;.descriptor.json
 *     &lt;workflowId&gt;/
 *       &lt;version&gt;/
 *         &lt;workflowId&gt;.workflow.json        (or .package.json for legacy)
 *         &lt;workflowId&gt;.descriptor.json
 *         &lt;extId&gt;.&lt;type&gt;.json              (e.g., abc123.langchain.json)
 *         &lt;extId&gt;.descriptor.json
 *     snippets/                              (may be at root, agent, or version level)
 *       &lt;snippetId&gt;.snippet.json
 * </pre>
 *
 * @since 6.0.0
 */
public class ZipResourceSource implements IResourceSource {

    private static final Logger log = Logger.getLogger(ZipResourceSource.class);

    /** Map from URI pattern authority to [file extension, step type label]. */
    private static final Map<Pattern, String[]> EXTENSION_TYPE_MAP = new LinkedHashMap<>();

    static {
        EXTENSION_TYPE_MAP.put(DICTIONARY_URI_PATTERN, new String[]{DICTIONARY_EXT, "ai.labs.dictionary"});
        EXTENSION_TYPE_MAP.put(BEHAVIOR_URI_PATTERN, new String[]{BEHAVIOR_EXT, "ai.labs.rules"});
        EXTENSION_TYPE_MAP.put(HTTPCALLS_URI_PATTERN, new String[]{HTTPCALLS_EXT, "ai.labs.apicalls"});
        EXTENSION_TYPE_MAP.put(LANGCHAIN_URI_PATTERN, new String[]{LLM_EXT, "ai.labs.llm"});
        EXTENSION_TYPE_MAP.put(PROPERTY_URI_PATTERN, new String[]{PROPERTY_EXT, "ai.labs.property"});
        EXTENSION_TYPE_MAP.put(OUTPUT_URI_PATTERN, new String[]{OUTPUT_EXT, "ai.labs.output"});
        EXTENSION_TYPE_MAP.put(MCPCALLS_URI_PATTERN, new String[]{MCPCALLS_EXT, "ai.labs.mcpcalls"});
        EXTENSION_TYPE_MAP.put(RAG_URI_PATTERN, new String[]{RAG_EXT, "ai.labs.rag"});
    }

    private static final String AGENT_FILE_ENDING = ".agent.json";
    private static final String DESCRIPTOR_FILE_ENDING = ".descriptor.json";

    private final Path rootDir;
    private final IJsonSerialization jsonSerialization;

    // Lazily loaded
    private AgentSourceData agentData;
    private List<WorkflowSourceData> workflowDataList;
    private List<SnippetSourceData> snippetDataList;

    public ZipResourceSource(Path unzippedDirectory, IJsonSerialization jsonSerialization) {
        this.rootDir = unzippedDirectory;
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public AgentSourceData readAgent() {
        if (agentData != null)
            return agentData;

        try {
            Path agentFilePath = findFileWithSuffix(rootDir, AGENT_FILE_ENDING);
            if (agentFilePath == null) {
                throw new RuntimeException("No agent file found in ZIP at " + rootDir);
            }

            String agentId = extractIdFromFilename(agentFilePath, AGENT_FILE_ENDING);
            String agentJson = readFile(agentFilePath);
            agentJson = AbstractBackupService.normalizeLegacyUris(agentJson);
            AgentConfiguration config = jsonSerialization.deserialize(agentJson, AgentConfiguration.class);
            String name = readNameFromDescriptor(agentFilePath.getParent(), agentId);

            agentData = new AgentSourceData(agentId, name, config);
            return agentData;
        } catch (IOException e) {
            throw new RuntimeException("Failed to read agent from ZIP", e);
        }
    }

    @Override
    public List<WorkflowSourceData> readWorkflows() {
        if (workflowDataList != null)
            return workflowDataList;

        AgentSourceData agent = readAgent();
        workflowDataList = new ArrayList<>();

        List<URI> workflowUris = agent.config().getWorkflows();
        for (int i = 0; i < workflowUris.size(); i++) {
            try {
                WorkflowSourceData wfData = readSingleWorkflow(workflowUris.get(i), i);
                if (wfData != null) {
                    workflowDataList.add(wfData);
                }
            } catch (Exception e) {
                log.warnf("Failed to read workflow %d from ZIP: %s", i, e.getMessage());
            }
        }

        return workflowDataList;
    }

    @Override
    public List<SnippetSourceData> readSnippets() {
        if (snippetDataList != null)
            return snippetDataList;
        snippetDataList = new ArrayList<>();

        Path snippetsDir = findSnippetsDir();
        if (snippetsDir == null || !Files.exists(snippetsDir)) {
            return snippetDataList;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(snippetsDir,
                p -> p.toString().endsWith("." + SNIPPET_EXT + ".json"))) {
            for (Path snippetFile : stream) {
                try {
                    String json = readFile(snippetFile);
                    PromptSnippet snippet = jsonSerialization.deserialize(json, PromptSnippet.class);
                    if (snippet != null && snippet.getName() != null) {
                        String snippetId = extractIdFromFilename(snippetFile,
                                "." + SNIPPET_EXT + ".json");
                        snippetDataList.add(new SnippetSourceData(
                                snippetId, snippet.getName(), snippet));
                    }
                } catch (Exception e) {
                    log.warnf("Failed to read snippet %s: %s",
                            snippetFile.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warnf("Failed to scan snippets directory: %s", e.getMessage());
        }

        return snippetDataList;
    }

    @Override
    public void close() {
        // Clean up the temporary unzipped directory
        try {
            if (rootDir != null && Files.exists(rootDir)) {
                deleteDirectoryRecursively(rootDir);
                log.debugf("Cleaned up temp import directory: %s", rootDir);
            }
        } catch (IOException e) {
            log.warnf("Failed to clean up temp import directory %s: %s", rootDir, e.getMessage());
        }
    }

    // ==================== Internal Helpers ====================

    private WorkflowSourceData readSingleWorkflow(URI workflowUri, int positionIndex) throws IOException {
        IResourceId workflowResourceId = RestUtilities.extractResourceId(workflowUri);
        if (workflowResourceId == null)
            return null;

        String workflowId = workflowResourceId.getId();
        String workflowVersion = String.valueOf(workflowResourceId.getVersion());

        // Find the workflow JSON file (see class Javadoc for directory layout)
        Path versionDir = findVersionDir(workflowId, workflowVersion);
        if (versionDir == null)
            return null;

        Path workflowFile = findFileWithSuffix(versionDir, ".workflow.json");
        if (workflowFile == null) {
            // Legacy: try .package.json
            workflowFile = findFileWithSuffix(versionDir, ".package.json");
        }
        if (workflowFile == null)
            return null;

        String workflowJson = readFile(workflowFile);
        workflowJson = AbstractBackupService.normalizeLegacyUris(workflowJson);
        WorkflowConfiguration config = jsonSerialization.deserialize(workflowJson, WorkflowConfiguration.class);
        String name = readNameFromDescriptor(versionDir, workflowId);

        // Read all extension configs from the workflow directory
        Map<String, ExtensionSourceData> extensions = readExtensions(versionDir, workflowJson);

        return new WorkflowSourceData(workflowId, name, positionIndex, config, extensions);
    }

    /**
     * Reads all extension configs for a workflow by scanning the workflow JSON for
     * URI references, then reading the corresponding files from disk.
     */
    private Map<String, ExtensionSourceData> readExtensions(Path workflowDir, String workflowJson) {
        Map<String, ExtensionSourceData> extensions = new LinkedHashMap<>();

        for (Map.Entry<Pattern, String[]> entry : EXTENSION_TYPE_MAP.entrySet()) {
            Pattern uriPattern = entry.getKey();
            String fileExtension = entry.getValue()[0];
            String stepType = entry.getValue()[1];

            try {
                List<URI> uris = extractResourcesUris(workflowJson, uriPattern);
                for (URI uri : uris) {
                    IResourceId resId = RestUtilities.extractResourceId(uri);
                    if (resId == null)
                        continue;

                    Path resourcePath = Paths.get(FileUtilities.buildPath(
                            workflowDir.toString(),
                            resId.getId() + "." + fileExtension + ".json"));

                    if (!Files.exists(resourcePath))
                        continue;

                    String contentJson = readFile(resourcePath);
                    String name = readNameFromDescriptor(workflowDir, resId.getId());

                    extensions.put(stepType, new ExtensionSourceData(
                            resId.getId(), name, fileExtension, stepType, contentJson));
                }
            } catch (Exception e) {
                log.debugf("Failed to read %s extensions: %s", fileExtension, e.getMessage());
            }
        }

        return extensions;
    }

    /**
     * Extracts resource URIs from a JSON string using the given pattern.
     */
    private List<URI> extractResourcesUris(String json, Pattern uriPattern) {
        List<URI> uris = new ArrayList<>();
        try {
            var matcher = uriPattern.matcher(json);
            while (matcher.find()) {
                String match = matcher.group();
                // Remove surrounding quotes
                String uri = match.substring(1, match.length() - 1);
                uris.add(URI.create(uri));
            }
        } catch (Exception e) {
            log.debugf("URI extraction failed: %s", e.getMessage());
        }
        return uris;
    }

    private Path findVersionDir(String workflowId, String version) {
        // Try under each subdirectory of root (agent dirs after unzipping)
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir, Files::isDirectory)) {
            for (Path agentDir : stream) {
                Path versionDir = Paths.get(agentDir.toString(), workflowId, version).normalize();
                if (Files.exists(versionDir))
                    return versionDir;
            }
        } catch (IOException e) {
            // fall through to direct path
        }
        // Try direct: root may be the agent dir itself
        Path direct = Paths.get(rootDir.toString(), workflowId, version).normalize();
        if (Files.exists(direct))
            return direct;
        return null;
    }

    /**
     * Finds the snippets directory. EDDI exports may place snippets at:
     * {@code <root>/snippets/}, {@code <root>/<agentId>/snippets/}, or
     * {@code <root>/<agentId>/<version>/snippets/}.
     */
    private Path findSnippetsDir() {
        Path direct = rootDir.resolve("snippets");
        if (Files.exists(direct))
            return direct;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(rootDir, Files::isDirectory)) {
            for (Path subDir : stream) {
                Path snippetsInAgent = subDir.resolve("snippets");
                if (Files.exists(snippetsInAgent))
                    return snippetsInAgent;

                // Check one level deeper
                try (DirectoryStream<Path> innerStream = Files.newDirectoryStream(subDir, Files::isDirectory)) {
                    for (Path innerDir : innerStream) {
                        Path snippetsInVersion = innerDir.resolve("snippets");
                        if (Files.exists(snippetsInVersion))
                            return snippetsInVersion;
                    }
                } catch (IOException ignored) {
                }
            }
        } catch (IOException ignored) {
        }

        return null;
    }

    private Path findFileWithSuffix(Path dir, String suffix) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir,
                p -> p.toString().endsWith(suffix))) {
            Iterator<Path> it = stream.iterator();
            if (it.hasNext())
                return it.next();
        } catch (IOException e) {
            log.debugf("Could not scan for %s in %s: %s", suffix, dir, e.getMessage());
        }
        return null;
    }

    private String extractIdFromFilename(Path filePath, String suffix) {
        String filename = filePath.getFileName().toString();
        return filename.substring(0, filename.length() - suffix.length());
    }

    private String readNameFromDescriptor(Path dir, String resourceId) {
        try {
            Path descriptorPath = Paths.get(dir.toString(), resourceId + DESCRIPTOR_FILE_ENDING);
            if (Files.exists(descriptorPath)) {
                String content = readFile(descriptorPath);
                DocumentDescriptor dd = jsonSerialization.deserialize(content, DocumentDescriptor.class);
                return dd.getName();
            }
        } catch (IOException e) {
            // name is optional
        }
        return null;
    }

    /**
     * Reads a file as a UTF-8 string. Preserves whitespace (including newlines) to
     * ensure accurate content comparison during diff generation.
     */
    private String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Recursively deletes a directory and all its contents.
     */
    private static void deleteDirectoryRecursively(Path dir) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path entry : stream) {
                if (Files.isDirectory(entry)) {
                    deleteDirectoryRecursively(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
        }
        Files.deleteIfExists(dir);
    }
}
