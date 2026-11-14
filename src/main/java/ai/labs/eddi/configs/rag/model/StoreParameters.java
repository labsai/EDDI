/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import ai.labs.eddi.secrets.VaultReferencePlacement;

import java.util.Map;
import java.util.Set;

/**
 * The {@code storeParameters} each vector store passes to its client as a
 * credential — the only ones a {@code ${vault:…}} reference may sit in.
 * <p>
 * A store parameter is resolved against the vault before the client is built,
 * and a value in {@code database}, {@code collectionName}, {@code indexName} or
 * {@code host} is sent as data: it comes back in the store's own error
 * ("database {@code <secret>} does not exist"), which RAG search and the
 * ingestion status return to the caller. So, as for model parameters (see
 * {@link VaultReferencePlacement}), a reference anywhere else is refused — at
 * save time by {@link RagConfiguration#validate()} and again, after global
 * variables are expanded, before the store is built.
 */
public final class StoreParameters {

    /**
     * What each store's builder takes as a credential. pgvector authenticates with
     * {@code user}/{@code password}, Elasticsearch with {@code apiKey} or
     * {@code userName}/{@code password}, Qdrant with {@code apiKey}, MongoDB Atlas
     * with the credentials in its {@code connectionString} — which is commonly the
     * secret as a whole. Chroma and the in-memory store take none.
     */
    private static final Map<String, Set<String>> CREDENTIALS = Map.of(
            "pgvector", Set.of("user", "password"),
            "mongodb-atlas", Set.of("connectionString"),
            "elasticsearch", Set.of("apiKey", "userName", "password"),
            "qdrant", Set.of("apiKey"),
            "chroma", Set.of(),
            "in-memory", Set.of());

    private StoreParameters() {
    }

    /**
     * The credential parameters of {@code storeType}; empty for an unknown type,
     * which therefore accepts no vault reference at all.
     */
    public static Set<String> credentialParameters(String storeType) {
        return storeType == null ? Set.of() : CREDENTIALS.getOrDefault(storeType.trim(), Set.of());
    }

    /**
     * A vault reference in a store parameter that is not a credential, described by
     * its key (never its value), or {@code null}.
     */
    public static String findMisplacedVaultReference(String storeType, Map<String, String> params) {
        Set<String> credentials = credentialParameters(storeType);
        String key = VaultReferencePlacement.findMisplaced(params, credentials);
        if (key == null) {
            return null;
        }
        return VaultReferencePlacement.describe("Vector store '" + (storeType == null ? "" : storeType.trim()) + "'",
                "storeParameters." + key, credentials);
    }

    /**
     * {@link #findMisplacedVaultReference} as an exception, for the build path.
     *
     * @throws VaultReferencePlacement.MisplacedVaultReferenceException
     *             naming the key
     */
    public static void requireCredentialsOnly(String storeType, Map<String, String> params) {
        String problem = findMisplacedVaultReference(storeType, params);
        if (problem != null) {
            throw new VaultReferencePlacement.MisplacedVaultReferenceException(problem);
        }
    }
}
