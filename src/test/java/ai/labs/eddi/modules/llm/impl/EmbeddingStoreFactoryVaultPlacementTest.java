/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.rag.model.StoreParameters;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultReferencePlacement.MisplacedVaultReferenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The vector-store half of finding 1 of the 2026-11 review: a vault reference
 * in {@code database}, {@code collectionName}, {@code indexName} or
 * {@code host} came back in the store's error. It is refused before anything is
 * resolved or connected.
 */
@DisplayName("EmbeddingStoreFactory — vault references only in store credentials")
class EmbeddingStoreFactoryVaultPlacementTest {

    private SecretResolver secretResolver;
    private GlobalVariableResolver globalVariableResolver;
    private EmbeddingStoreFactory factory;

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        factory = new EmbeddingStoreFactory(globalVariableResolver, secretResolver);
    }

    private static RagConfiguration store(String type, Map<String, String> params) {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setStoreNamespace("id");
        config.setStoreType(type);
        config.setStoreParameters(params);
        return config;
    }

    @Test
    @DisplayName("the exploit: a reference in pgvector's database is refused, naming only the key, before it is resolved")
    void referenceInDatabaseIsRefused() {
        var refusal = assertThrows(MisplacedVaultReferenceException.class,
                () -> factory.getOrCreate("kb-1", store("pgvector", Map.of("password", "p", "database", "${vault:any-secret}"))));

        assertTrue(refusal.getMessage().contains("'storeParameters.database'"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains("any-secret"));
        verify(secretResolver, never()).resolveSecrets(any());
    }

    @Test
    @DisplayName("a ${vars:} that expands to a reference is caught after expansion")
    void variableIndirectionIsRefused() {
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> {
            Map<String, String> expanded = new HashMap<>(inv.getArgument(0));
            expanded.replaceAll((k, v) -> "${vars:index}".equals(v) ? "${vault:any-secret}" : v);
            return expanded;
        });

        assertThrows(MisplacedVaultReferenceException.class,
                () -> factory.getOrCreate("kb-2", store("elasticsearch", Map.of("apiKey", "k", "indexName", "${vars:index}"))));
        verify(secretResolver, never()).resolveSecrets(any());
    }

    @Test
    @DisplayName("credential parameters per store type")
    void credentialParameters() {
        assertNull(StoreParameters.findMisplacedVaultReference("pgvector", Map.of("user", "${vault:u}", "password", "${vault:p}")));
        assertNull(StoreParameters.findMisplacedVaultReference("mongodb-atlas", Map.of("connectionString", "${vault:atlas}")));
        assertNull(StoreParameters.findMisplacedVaultReference("elasticsearch",
                Map.of("apiKey", "${vault:k}", "userName", "${vault:u}", "password", "${vault:p}")));
        assertNull(StoreParameters.findMisplacedVaultReference("qdrant", Map.of("apiKey", "${vault:k}")));
        assertTrue(StoreParameters.findMisplacedVaultReference("qdrant", Map.of("host", "${vault:h}")).contains("'storeParameters.host'"));
        assertTrue(StoreParameters.findMisplacedVaultReference("chroma", Map.of("collectionName", "${vault:c}"))
                .contains("takes no credential parameter"));
        assertTrue(StoreParameters.findMisplacedVaultReference("mongodb-atlas", Map.of("connectionString", "c", "databaseName", "${vault:d}"))
                .contains("'storeParameters.databaseName'"));
    }

    @Test
    @DisplayName("save time: validate() refuses a misplaced store reference")
    void validateRefuses() {
        var config = store("pgvector", Map.of("password", "${vault:p}", "table", "${vault:any-secret}"));

        var refusal = assertThrows(IllegalArgumentException.class, config::validate);
        assertTrue(refusal.getMessage().contains("'storeParameters.table'"), refusal.getMessage());
    }
}
