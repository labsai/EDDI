/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.IRestRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.RagSourceIngestionService;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.secrets.SecretResolver;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An older configuration — from an archive, a sync or an upgrade, which its
 * owner cannot edit first — still imports and still updates; only an
 * interactive save refuses what could only fail later.
 */
@DisplayName("Older knowledge base configurations stay importable")
class RestRagStoreLegacyConfigTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private IRagStore ragStore;
    private RestRagStore restRagStore;

    @BeforeEach
    void setUp() throws Exception {
        ragStore = mock(IRagStore.class);
        restRagStore = new RestRagStore(ragStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ResourceAccessGuard.class), mock(RagSourceIngestionService.class),
                new EmbeddingStoreFactory(mock(GlobalVariableResolver.class), mock(SecretResolver.class), 1000));
        when(ragStore.read(eq(KB_ID), anyInt())).thenReturn(stored());
        when(ragStore.update(eq(KB_ID), anyInt(), any(RagConfiguration.class))).thenReturn(2);
    }

    private static RagConfiguration stored() {
        var config = new RagConfiguration();
        config.setName("docs");
        config.setStoreNamespace("id");
        return config;
    }

    /**
     * What an older version accepted: every value here is refused by an interactive
     * save.
     */
    private static RagConfiguration old() {
        var config = new RagConfiguration();
        config.setName("  ");
        config.setStoreType("legacy-store");
        config.setStoreParameters(Map.of("host", "db:5432"));
        config.setMinScore(1.5);
        config.setMaxResults(0);
        return config;
    }

    @Test
    @DisplayName("the one update older configurations go through — import, sync and upgrade — is the lenient one on the real store")
    void olderConfigUpdateIsLenient() {
        RagConfiguration config = old();

        assertEquals(200, RestRagStore.updateOlderConfig(restRagStore, KB_ID, 1, config).getStatus());
        assertEquals("legacy-store", config.getStoreType());
    }

    @Test
    @DisplayName("and the interactive one for any other store, such as a test double")
    void otherStoresGetTheInteractiveUpdate() {
        IRestRagStore double_ = mock(IRestRagStore.class);
        RagConfiguration config = old();

        RestRagStore.updateOlderConfig(double_, KB_ID, 1, config);

        verify(double_).updateRag(KB_ID, 1, config);
    }

    // That the upgrade (and with it a live sync) and an archive merge reach the
    // store
    // through this helper is proven where they run: UpgradeExecutorTest
    // .ragUpdateIsLenient and RestImportServiceLegacyRagTest.mergeUpdateIsLenient
    // drive the real dispatch against a store whose interactive update refuses.

    @Test
    @DisplayName("an interactive save refuses it with a 400")
    void interactiveSaveRefuses() {
        assertThrows(BadRequestException.class, () -> restRagStore.updateRag(KB_ID, 1, old()));
    }

    @Test
    @DisplayName("the lenient update accepts it: retrieval settings are brought into range, the rest is kept")
    void lenientUpdateAccepts() {
        RagConfiguration config = old();

        Response response = assertDoesNotThrow(() -> restRagStore.updateRagLenient(KB_ID, 1, config));

        assertEquals(200, response.getStatus());
        assertEquals(1.0, config.getMinScore());
        assertEquals(5, config.getMaxResults());
        assertEquals("legacy-store", config.getStoreType());
        assertEquals("db:5432", config.getStoreParameters().get("host"));
    }
}
