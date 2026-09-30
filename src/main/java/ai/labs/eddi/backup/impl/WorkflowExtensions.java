/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.utils.RestUtilities;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.backup.impl.AbstractBackupService.BEHAVIOR_EXT;
import static ai.labs.eddi.backup.impl.AbstractBackupService.DICTIONARY_EXT;
import static ai.labs.eddi.backup.impl.AbstractBackupService.HTTPCALLS_EXT;
import static ai.labs.eddi.backup.impl.AbstractBackupService.LLM_EXT;
import static ai.labs.eddi.backup.impl.AbstractBackupService.MCPCALLS_EXT;
import static ai.labs.eddi.backup.impl.AbstractBackupService.OUTPUT_EXT;
import static ai.labs.eddi.backup.impl.AbstractBackupService.PARSER_EXT;
import static ai.labs.eddi.backup.impl.AbstractBackupService.PROPERTY_EXT;
import static ai.labs.eddi.backup.impl.AbstractBackupService.RAG_EXT;

/**
 * Single source of truth for <em>how a workflow points at its extension
 * configs</em> and for the per-type metadata the backup package needs.
 * <p>
 * Every producer and consumer of
 * {@link ai.labs.eddi.backup.IResourceSource.WorkflowSourceData#extensions()}
 * derives its map keys from {@link #scan(WorkflowConfiguration)}, so source
 * (ZIP or remote instance) and target (local store) keys are guaranteed to
 * join. Before this class existed the ZIP side keyed the map by resource-store
 * authority ({@code ai.labs.rules}) while the target side keyed it by the
 * workflow step type URI ({@code eddi://ai.labs.behavior}), so no extension
 * could ever match and every sync reported CREATE.
 *
 * <h3>Where the URI actually lives</h3> A workflow step keeps its extension
 * reference in the step's {@code config} map — {@code config.uri} — not in its
 * {@code extensions} map; {@code extensions} carries nested things such as the
 * parser's {@code dictionaries} list, where each entry has a {@code config.uri}
 * of its own. {@link #scan(WorkflowConfiguration)} walks both, so a parser's
 * regular dictionaries are found alongside the top-level references.
 *
 * <h3>Key shape</h3> {@code <stepType>#<occurrence>/<path>}, e.g.
 * {@code eddi://ai.labs.behavior#0/config} or
 * {@code eddi://ai.labs.parser#0/extensions/dictionaries/0/config}. The
 * occurrence ordinal counts steps of the same type within the workflow, so a
 * workflow with two {@code eddi://ai.labs.httpcalls} steps keeps both instead
 * of collapsing them onto one key.
 *
 * <h3>References inside a document</h3> A parser document names its own
 * dictionaries. {@link #scanDocument} finds them under the parser's key plus
 * {@value #DOCUMENT_MARKER} and the path inside the document, e.g.
 * {@code eddi://ai.labs.parser#0/config/@document/extensions/dictionaries/0/config}.
 * Both sides derive those keys the same way, so such a dictionary is matched,
 * previewed, created and updated like any other resource — only it is
 * referenced from the parser document, not from the workflow, so nothing in the
 * workflow is repointed for it ({@link #isInDocument}).
 *
 * @since 6.0.0
 */
final class WorkflowExtensions {

    /** The map key under which a workflow step stores its extension URI. */
    static final String KEY_URI = "uri";

    /** Separates a document's own key from the path of a reference inside it. */
    static final String DOCUMENT_MARKER = "/@document";

    /** Guards against a pathological (or cyclic-looking) nested config. */
    private static final int MAX_DEPTH = 10;

    private WorkflowExtensions() {
    }

    /**
     * Per extension type: the authority of its resource URI (the key), the file
     * extension used inside an export ZIP, and the REST path on a remote EDDI
     * instance.
     *
     * @param resourceAuthority
     *            authority of the {@code eddi://} resource URI, e.g.
     *            {@code ai.labs.rules}
     * @param fileExtension
     *            file extension inside an export ZIP, e.g. {@code behavior} — also
     *            the value carried as
     *            {@link ai.labs.eddi.backup.IResourceSource.ExtensionSourceData#type()}
     * @param restPath
     *            store path on a remote instance, e.g. {@code /rulestore/rulesets/}
     */
    record ExtensionType(String resourceAuthority, String fileExtension, String restPath) {
    }

    private static final Map<String, ExtensionType> BY_AUTHORITY = new LinkedHashMap<>();

    static {
        register("ai.labs.parser", PARSER_EXT, "/parserstore/parsers/");
        register("ai.labs.dictionary", DICTIONARY_EXT, "/dictionarystore/dictionaries/");
        register("ai.labs.rules", BEHAVIOR_EXT, "/rulestore/rulesets/");
        register("ai.labs.apicalls", HTTPCALLS_EXT, "/apicallstore/apicalls/");
        register("ai.labs.llm", LLM_EXT, "/llmstore/llms/");
        register("ai.labs.property", PROPERTY_EXT, "/propertysetterstore/propertysetters/");
        register("ai.labs.output", OUTPUT_EXT, "/outputstore/outputsets/");
        register("ai.labs.mcpcalls", MCPCALLS_EXT, "/mcpcallsstore/mcpcalls/");
        register("ai.labs.rag", RAG_EXT, "/ragstore/rags/");
    }

    private static void register(String authority, String fileExtension, String restPath) {
        BY_AUTHORITY.put(authority, new ExtensionType(authority, fileExtension, restPath));
    }

    /**
     * A single extension reference found in a workflow.
     *
     * @param key
     *            canonical map key, stable across source and target
     * @param stepIndex
     *            0-based index of the workflow step the reference was found in
     * @param stepType
     *            the step's {@code type} URI as stored, e.g.
     *            {@code eddi://ai.labs.behavior}
     * @param container
     *            the live map that holds the {@code uri} entry — writing to it
     *            repoints the workflow at another version
     * @param extensionUri
     *            the referenced resource URI
     * @param resourceId
     *            id + version extracted from {@code extensionUri}
     * @param type
     *            the extension type metadata; never null (unregistered authorities
     *            do not produce a reference)
     */
    record ExtensionRef(String key,
            int stepIndex,
            String stepType,
            Map<String, Object> container,
            URI extensionUri,
            IResourceId resourceId,
            ExtensionType type) {

        String fileExtension() {
            return type.fileExtension();
        }

        /** Repoints this reference at a new version of the same resource. */
        void repointTo(URI newExtensionUri) {
            container.put(KEY_URI, newExtensionUri.toString());
        }
    }

    /**
     * The versioned resource URI of a resource of this file-extension type, or null
     * for a type that is not registered.
     */
    static URI resourceUri(String fileExtension, String resourceId, int version) {
        for (ExtensionType type : BY_AUTHORITY.values()) {
            if (type.fileExtension().equals(fileExtension)) {
                return URI.create("eddi://" + type.resourceAuthority() + type.restPath() + resourceId
                        + "?version=" + version);
            }
        }
        return null;
    }

    /** Metadata for the type of resource a URI addresses, or null if unknown. */
    static ExtensionType typeOf(URI resourceUri) {
        if (resourceUri == null) {
            return null;
        }
        String authority = resourceUri.getHost() != null ? resourceUri.getHost() : resourceUri.getAuthority();
        return authority != null ? BY_AUTHORITY.get(authority) : null;
    }

    /**
     * All extension references a workflow carries, in step order. Null-safe: a
     * config, step list, step, or map that is null simply contributes nothing.
     */
    static List<ExtensionRef> scan(WorkflowConfiguration config) {
        List<ExtensionRef> refs = new ArrayList<>();
        if (config == null || config.getWorkflowSteps() == null) {
            return refs;
        }

        Map<String, Integer> occurrences = new HashMap<>();
        List<WorkflowConfiguration.WorkflowStep> steps = config.getWorkflowSteps();
        for (int i = 0; i < steps.size(); i++) {
            WorkflowConfiguration.WorkflowStep step = steps.get(i);
            if (step == null || step.getType() == null) {
                continue;
            }
            String stepType = step.getType().toString();
            int occurrence = occurrences.merge(stepType, 1, Integer::sum) - 1;
            String base = stepType + "#" + occurrence;

            collect(refs, step.getConfig(), base + "/config", i, stepType, 0);
            collect(refs, step.getExtensions(), base + "/extensions", i, stepType, 0);
        }
        return refs;
    }

    /**
     * The resources a referenced document names itself — a parser document's
     * dictionaries — keyed under the document's own key.
     * <p>
     * Documents are not followed further: a reference to another parser inside a
     * parser document is left out, which also keeps a self-referencing document
     * from recursing.
     *
     * @param document
     *            the referenced document, parsed into maps and lists
     */
    static List<ExtensionRef> scanDocument(ExtensionRef owner, Object document) {
        List<ExtensionRef> refs = new ArrayList<>();
        if (owner == null || document == null) {
            return refs;
        }
        collect(refs, document, owner.key() + DOCUMENT_MARKER, owner.stepIndex(), owner.stepType(), 0);
        refs.removeIf(ref -> PARSER_EXT.equals(ref.fileExtension()));
        return refs;
    }

    /**
     * Whether a key names a reference held inside a document rather than by the
     * workflow itself. Writing such a resource repoints the document that names it,
     * never a workflow step.
     */
    static boolean isInDocument(String key) {
        return key != null && key.contains(DOCUMENT_MARKER + "/");
    }

    @SuppressWarnings("unchecked")
    private static void collect(List<ExtensionRef> refs, Object node, String path,
                                int stepIndex, String stepType, int depth) {
        if (node == null || depth > MAX_DEPTH) {
            return;
        }

        if (node instanceof Map<?, ?> map) {
            Object uriValue = map.get(KEY_URI);
            if (uriValue instanceof String uriString && !uriString.isBlank()) {
                addRef(refs, (Map<String, Object>) map, uriString, path, stepIndex, stepType);
            }
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (KEY_URI.equals(entry.getKey())) {
                    continue;
                }
                collect(refs, entry.getValue(), path + "/" + entry.getKey(), stepIndex, stepType, depth + 1);
            }
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                collect(refs, list.get(i), path + "/" + i, stepIndex, stepType, depth + 1);
            }
        }
    }

    private static void addRef(List<ExtensionRef> refs, Map<String, Object> container, String uriString,
                               String path, int stepIndex, String stepType) {
        URI extensionUri;
        try {
            extensionUri = URI.create(uriString);
        } catch (IllegalArgumentException e) {
            return;
        }

        ExtensionType type = typeOf(extensionUri);
        if (type == null) {
            // Not a resource type the backup package knows how to move (e.g. a
            // built-in parser dictionary such as ai.labs.parser.dictionaries.integer,
            // which is a type, not a stored resource). Leaving it out keeps the key space
            // identical on
            // both sides, which is what makes source and target join at all.
            return;
        }

        IResourceId resourceId;
        try {
            resourceId = RestUtilities.extractResourceId(extensionUri);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (resourceId == null || resourceId.getId() == null) {
            return;
        }

        refs.add(new ExtensionRef(path, stepIndex, stepType, container, extensionUri, resourceId, type));
    }
}
