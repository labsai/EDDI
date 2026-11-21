/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.index;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.utils.RestUtilities;

import java.net.URI;
import java.util.Set;
import java.util.function.Function;

/**
 * The one rule that decides whether a RAG configuration may hold document
 * chunks — a <em>document index</em> — and whether a RAG configuration is one.
 * Every check in every direction calls this class and nothing else: saving an
 * LLM task that names an index, saving the RAG configuration itself, saving a
 * workflow, retrieval at runtime, the deployment's default index at startup,
 * and every write of document chunks.
 *
 * <h2>Why a flag, and why it is enforced everywhere</h2>
 * <p>
 * Document chunks are a user's or a conversation's files. They must never be
 * returned by an agent's ordinary knowledge-base retrieval, which answers every
 * user of the agent. They are written under their own chunk tag
 * ({@link KnowledgeBaseStorage#docLibKbId}), in a RAG configuration flagged
 * {@code documentIndex}, and no workflow may use such a configuration as a RAG
 * step. Enforced in one place only, the separation would hold in one edit order
 * and not the other (flag first, workflow second — or the reverse).
 *
 * <h2>Eligible</h2>
 * <p>
 * A configuration is eligible iff all of these hold:
 * <ol>
 * <li>{@code documentIndex} is {@code true};</li>
 * <li>it uses the per-id layout ({@link KnowledgeBaseStorage#usesIdNamespace}),
 * so its store is keyed by its id and its chunks are told apart by tag;</li>
 * <li>its retrieval is filtered by that tag
 * ({@link KnowledgeBaseStorage#filtersRetrieval}) — which rules out a MongoDB
 * Atlas default collection, whose index declares no filter field;</li>
 * <li>its store type can filter and delete by metadata
 * ({@link #METADATA_FILTERING_STORE_TYPES});</li>
 * <li>it has no ingestion {@code sources}: an index holds document chunks only,
 * so no source pipeline ever sweeps or replaces chunks in it.</li>
 * </ol>
 */
public final class DocumentIndexEligibility {

    /**
     * Store types whose langchain4j store filters a search, and deletes, by chunk
     * metadata. {@code mongodb-atlas} filters only on fields its search index
     * declares; a document index on an explicit Atlas collection needs
     * {@code kbId}, {@code sourceKey} and {@code documentId} declared there (see
     * {@code docs/rag.md}). An unknown store type is never eligible.
     */
    public static final Set<String> METADATA_FILTERING_STORE_TYPES = Set.of("in-memory", "pgvector", "elasticsearch", "qdrant", "chroma",
            "mongodb-atlas");

    /** The workflow-step and resource host of a RAG configuration. */
    public static final String RAG_RESOURCE_HOST = "ai.labs.rag";

    private DocumentIndexEligibility() {
    }

    /** Whether the configuration is flagged as a document index. */
    public static boolean isDocumentIndex(RagConfiguration config) {
        return config != null && Boolean.TRUE.equals(config.getDocumentIndex());
    }

    /** Whether the configuration may hold document chunks. */
    public static boolean isEligible(RagConfiguration config) {
        return ineligibility(config) == null;
    }

    /**
     * Why the configuration may not hold document chunks, or {@code null} when it
     * may. The reason is a sentence fit for a 400 or a startup failure.
     */
    public static String ineligibility(RagConfiguration config) {
        if (config == null) {
            return "the RAG configuration does not exist";
        }
        if (!isDocumentIndex(config)) {
            return "the RAG configuration is not flagged \"documentIndex\": true";
        }
        String storeType = config.getStoreType();
        if (storeType == null || !METADATA_FILTERING_STORE_TYPES.contains(storeType)) {
            return "a document index needs a vector store that can filter by metadata; '" + storeType + "' cannot (supported: "
                    + String.join(", ", METADATA_FILTERING_STORE_TYPES.stream().sorted().toList()) + ")";
        }
        if (!KnowledgeBaseStorage.usesIdNamespace(config)) {
            return "a document index must use storeNamespace '" + KnowledgeBaseStorage.NAMESPACE_ID
                    + "'; the 6.5.0 name layout does not tell its chunks apart";
        }
        if (!KnowledgeBaseStorage.filtersRetrieval(config)) {
            return "a document index cannot use a MongoDB Atlas default collection, whose search index declares no filter field; "
                    + "set storeParameters.collectionName and declare kbId, sourceKey and documentId as filter fields";
        }
        if (config.getSources() != null && !config.getSources().isEmpty()) {
            return "a document index holds document chunks only and may not have ingestion sources";
        }
        return null;
    }

    /**
     * A reference to a RAG configuration, as {@code documents.index.rag} and
     * {@code eddi.documents.index.default-rag} hold it.
     *
     * @param id
     *            the RAG configuration's id
     * @param version
     *            the version named, or {@code null} for the current one
     */
    public record Reference(String id, Integer version) {
    }

    /**
     * Parses a reference: a RAG resource URI
     * ({@code eddi://ai.labs.rag/ragstore/rags/<id>}, {@code ?version=<n>}
     * optional) or a bare id.
     *
     * @throws IllegalArgumentException
     *             when the reference is blank, or names no RAG configuration id
     */
    public static Reference parseReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("a document index reference is blank");
        }
        String trimmed = reference.strip();
        if (!trimmed.contains("://")) {
            if (!RestUtilities.isValidId(trimmed)) {
                throw new IllegalArgumentException("'" + trimmed + "' is neither a RAG configuration id nor a RAG configuration URI");
            }
            return new Reference(trimmed, null);
        }
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("'" + trimmed + "' is not a RAG configuration URI", e);
        }
        if (!RAG_RESOURCE_HOST.equals(uri.getHost())) {
            throw new IllegalArgumentException("'" + trimmed + "' does not name a RAG configuration (expected eddi://" + RAG_RESOURCE_HOST
                    + "/ragstore/rags/<id>)");
        }
        IResourceStore.IResourceId id = RestUtilities.extractResourceId(uri);
        if (id == null || id.getId() == null) {
            throw new IllegalArgumentException("'" + trimmed + "' names no RAG configuration id");
        }
        // extractResourceId reports an absent version as 0.
        Integer version = id.getVersion() == null || id.getVersion() < 1 ? null : id.getVersion();
        return new Reference(id.getId(), version);
    }

    /**
     * Why the configuration a reference names may not hold document chunks, or
     * {@code null} when it may: the reference is parsed, the configuration looked
     * up, and the rule applied.
     *
     * @param lookup
     *            the configuration a reference names, or {@code null} when there is
     *            none
     */
    public static String referenceIneligibility(String reference, Function<Reference, RagConfiguration> lookup) {
        Reference parsed;
        try {
            parsed = parseReference(reference);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        RagConfiguration config = lookup.apply(parsed);
        if (config == null) {
            return "RAG configuration " + parsed.id() + (parsed.version() == null ? "" : " version " + parsed.version()) + " does not exist";
        }
        return ineligibility(config);
    }
}
