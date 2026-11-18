/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore;
import ai.labs.eddi.modules.rag.VectorSpaceGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Finding the other knowledge bases that keep vectors in the same place:
 * locations are compared as resolved, and a scan that could not be completed is
 * an error — a sharer that is not seen is the one whose data is lost.
 */
@DisplayName("KnowledgeBaseStorageGuard.sharersOf")
class KnowledgeBaseStorageGuardSharersTest {

    private static final String SELF = "65f0aa11bb22cc33dd44ee01";

    private IRagStore ragStore;
    private IDocumentDescriptorStore descriptorStore;
    private KnowledgeBaseStorageGuard guard;
    private VectorSpaceGuard locations;
    private final Map<String, RagConfiguration> byId = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        ragStore = mock(IRagStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        guard = new KnowledgeBaseStorageGuard(ragStore, descriptorStore, mock(ResourceAccessGuard.class));
        GlobalVariableResolver variables = mock(GlobalVariableResolver.class);
        when(variables.resolveAll(any())).thenAnswer(call -> {
            Map<String, String> resolved = new HashMap<>();
            call.<Map<String, String>>getArgument(0)
                    .forEach((key, value) -> resolved.put(key, "${vars:db-host}".equals(value) ? "db.internal" : value));
            return resolved;
        });
        locations = new VectorSpaceGuard(mock(IIngestionStateStore.class), variables);
        when(ragStore.read(anyString(), anyInt())).thenAnswer(call -> {
            RagConfiguration config = byId.get(call.<String>getArgument(0));
            if (config == null) {
                throw new IResourceStore.ResourceNotFoundException("gone");
            }
            return config;
        });
    }

    private static RagConfiguration kb(String host) {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setStoreNamespace("id");
        config.setStoreType("pgvector");
        config.setStoreParameters(Map.of("host", host, "table", "shared", "password", "x"));
        return config;
    }

    private void listed(String... ids) throws Exception {
        List<DocumentDescriptor> descriptors = new ArrayList<>();
        for (String id : ids) {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.rag/ragstore/rags/" + id + "?version=1"));
            descriptors.add(descriptor);
        }
        when(descriptorStore.readDescriptors(eq("ai.labs.rag"), isNull(), eq(0), anyInt(), anyBoolean())).thenReturn(descriptors);
    }

    @Test
    @DisplayName("a ${vars:...} host and the host it stands for are one location")
    void variablesAreResolvedBeforeComparing() throws Exception {
        byId.put("65f0aa11bb22cc33dd44ee02", kb("db.internal"));
        listed(SELF, "65f0aa11bb22cc33dd44ee02");

        var sharers = guard.sharersOf(SELF, kb("${vars:db-host}"), locations::locationOf);

        assertEquals(List.of(new KnowledgeBaseStorageGuard.Sharer("65f0aa11bb22cc33dd44ee02", "65f0aa11bb22cc33dd44ee02")), sharers);
    }

    @Test
    @DisplayName("compared as written, the same two are not recognised — which is why the resolved comparison exists")
    void asWrittenTheyDiffer() throws Exception {
        byId.put("65f0aa11bb22cc33dd44ee02", kb("db.internal"));
        listed(SELF, "65f0aa11bb22cc33dd44ee02");

        assertEquals(List.of(), guard.sharersOf(SELF, kb("${vars:db-host}")));
    }

    @Test
    @DisplayName("another host, or another table, is not a sharer")
    void otherLocationsAreNot() throws Exception {
        byId.put("65f0aa11bb22cc33dd44ee03", kb("other.internal"));
        listed(SELF, "65f0aa11bb22cc33dd44ee03");

        assertEquals(List.of(), guard.sharersOf(SELF, kb("db.internal"), locations::locationOf));
    }

    @Test
    @DisplayName("a knowledge base that cannot be read makes the scan incomplete, and is named for the log")
    void unreadableKnowledgeBaseMakesTheScanIncomplete() throws Exception {
        byId.put("65f0aa11bb22cc33dd44ee02", kb("db.internal"));
        when(ragStore.read(eq("65f0aa11bb22cc33dd44ee04"), anyInt())).thenThrow(new IllegalStateException("document is corrupt"));
        listed(SELF, "65f0aa11bb22cc33dd44ee02", "65f0aa11bb22cc33dd44ee04");

        var incomplete = assertThrows(KnowledgeBaseStorageGuard.SharerScanIncompleteException.class,
                () -> guard.sharersOf(SELF, kb("db.internal"), locations::locationOf));

        assertEquals(List.of("65f0aa11bb22cc33dd44ee04"), incomplete.unreadable());
        // The message carries no knowledge base id: it is for the log.
        assertEquals("1 knowledge base(s) could not be read", incomplete.getMessage());
    }

    @Test
    @DisplayName("a knowledge base whose variables cannot be resolved right now makes the scan incomplete too")
    void unresolvableLocationMakesTheScanIncomplete() throws Exception {
        var other = kb("db.internal");
        other.setStoreParameters(Map.of("host", "${vars:db-host}", "table", "shared"));
        byId.put("65f0aa11bb22cc33dd44ee02", other);
        GlobalVariableResolver failing = mock(GlobalVariableResolver.class);
        when(failing.resolveAll(any())).thenThrow(new IllegalStateException("variable store down"));
        VectorSpaceGuard broken = new VectorSpaceGuard(mock(IIngestionStateStore.class), failing);
        listed(SELF, "65f0aa11bb22cc33dd44ee02");

        assertThrows(KnowledgeBaseStorageGuard.SharerScanIncompleteException.class,
                () -> guard.sharersOf(SELF, kb("db.internal"), (id, config) -> id.equals(SELF)
                        ? KnowledgeBaseStorage.vectorSpaceLocation(id, config)
                        : broken.locationOf(id, config)));
    }

    @Test
    @DisplayName("a deleted knowledge base keeps nothing there and is skipped")
    void deletedKnowledgeBasesAreSkipped() throws Exception {
        listed(SELF, "65f0aa11bb22cc33dd44ee05");

        assertEquals(List.of(), guard.sharersOf(SELF, kb("db.internal"), locations::locationOf));
    }
}
