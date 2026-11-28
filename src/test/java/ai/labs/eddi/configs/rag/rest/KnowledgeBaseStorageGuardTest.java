/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.rest;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("KnowledgeBaseStorageGuard — which store a knowledge base may address")
class KnowledgeBaseStorageGuardTest {

    private static final String SELF = "65f0aa11bb22cc33dd44ee55";
    private static final String OTHER = "65f0aa11bb22cc33dd44ee66";

    private IRagStore ragStore;
    private IDocumentDescriptorStore descriptorStore;
    private ResourceAccessGuard accessGuard;
    private KnowledgeBaseStorageGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        ragStore = mock(IRagStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        when(descriptorStore.readDescriptors(eq("ai.labs.rag"), isNull(), anyInt(), anyInt(), anyBoolean())).thenReturn(List.of());
        guard = new KnowledgeBaseStorageGuard(ragStore, descriptorStore, accessGuard);
    }

    private static RagConfiguration kb(String name, String namespace, Map<String, String> storeParameters) {
        var config = new RagConfiguration();
        config.setName(name);
        config.setStoreType("pgvector");
        config.setStoreNamespace(namespace);
        config.setStoreParameters(storeParameters == null ? null : new HashMap<>(storeParameters));
        return config;
    }

    /** Another knowledge base exists, with an explicit table. */
    private void otherKnowledgeBaseUsesTable(String table) throws Exception {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(URI.create("eddi://ai.labs.rag/ragstore/rags/" + OTHER + "?version=2"));
        when(descriptorStore.readDescriptors(eq("ai.labs.rag"), isNull(), eq(0), anyInt(), anyBoolean())).thenReturn(List.of(descriptor));
        when(ragStore.read(OTHER, 2)).thenReturn(kb("other", "id", Map.of("table", table)));
    }

    @Nested
    @DisplayName("a new knowledge base")
    class New {

        @Test
        @DisplayName("is addressed by its id")
        void getsTheIdLayout() {
            var config = kb("alpha", null, null);
            guard.prepareNew(config, true);
            assertEquals("id", config.getStoreNamespace());
        }

        @Test
        @DisplayName("cannot ask for the name layout, which is how it would share another's store")
        void refusesTheNameLayoutOnCreate() {
            assertThrows(BadRequestException.class, () -> guard.prepareNew(kb("alpha", "name", null), true));
        }

        @Test
        @DisplayName("a duplicate or an import of a name-layout knowledge base gets the id layout instead")
        void duplicateAndImportAreMovedToTheIdLayout() {
            var copy = kb("alpha", "name", null);
            guard.prepareNew(copy, false);
            assertEquals("id", copy.getStoreNamespace());
        }

        @Test
        @DisplayName("may not name a location in EDDI's reserved namespace")
        void refusesReservedExplicitNames() {
            assertThrows(BadRequestException.class, () -> guard.prepareNew(kb("beta", null, Map.of("table", "eddi_kb_alpha")), true));
            assertThrows(BadRequestException.class,
                    () -> guard.prepareNew(kb("beta", null, Map.of("table", "EDDI_KBID_" + OTHER)), true));
        }

        /**
         * PR #945 review: the identifier check skipped any value containing "${", so a
         * reference with SQL around it reached the pgvector store, which puts the table
         * into SQL unquoted. A whole reference was no safer: global variables are
         * writable by editors, so it could resolve to another knowledge base's table
         * after the checks here had passed on the reference text.
         */
        @Test
        @DisplayName("a location given as a ${...} reference is refused, with or without text around it")
        void refusesReferencesAsTheLocation() {
            for (String table : List.of("x${vars:a}; DROP TABLE y --", "${vars:table}", "${vault:t}")) {
                var e = assertThrows(BadRequestException.class, () -> guard.prepareNew(kb("beta", null, Map.of("table", table)), true),
                        table);
                assertTrue(e.getMessage().contains("literal"), e.getMessage());
            }
            var qdrant = kb("beta", null, Map.of("collectionName", "${vars:c}"));
            qdrant.setStoreType("qdrant");
            assertThrows(BadRequestException.class, () -> guard.prepareNew(qdrant, true));
        }

        @Test
        @DisplayName("a quoted, schema-qualified or padded spelling of a reserved table is refused too")
        void refusesDisguisedReservedNames() {
            for (String table : new String[]{"\"eddi_kb_alpha\"", "public.eddi_kb_alpha", "PUBLIC.EDDI_KB_alpha", "  eddi_kb_alpha ",
                    "x; DROP TABLE y", "/**/eddi_kb_alpha"}) {
                assertThrows(BadRequestException.class, () -> guard.prepareNew(kb("beta", null, Map.of("table", table)), true), table);
            }
        }

        @Test
        @DisplayName("a reference stored before the rule is not refused on a save that leaves it alone")
        void storedReferenceIsNotRejudged() {
            // What it resolves to is checked when the store is built
            // (EmbeddingStoreFactory).
            var stored = Map.of("table", "${vars:team-table}");
            assertDoesNotThrow(() -> guard.prepareUpdate(SELF, kb("beta", "id", stored), kb("beta", "id", stored)));
        }

        @Test
        @DisplayName("the public schema does not hide a collision with another knowledge base's table")
        void refusesACollisionBehindTheDefaultSchema() throws Exception {
            otherKnowledgeBaseUsesTable("team_docs");
            when(accessGuard.hasAccess(OTHER, AccessLevel.EDIT)).thenReturn(false);

            assertThrows(BadRequestException.class, () -> guard.prepareNew(kb("beta", null, Map.of("table", "public.Team_Docs")), true));
        }

        @Test
        @DisplayName("may not name another knowledge base's location without EDIT on it")
        void refusesACollisionWithoutEditOnTheOther() throws Exception {
            otherKnowledgeBaseUsesTable("team_docs");
            when(accessGuard.hasAccess(OTHER, AccessLevel.EDIT)).thenReturn(false);

            var e = assertThrows(BadRequestException.class, () -> guard.prepareNew(kb("beta", null, Map.of("table", "TEAM_DOCS")), true));
            assertTrue(e.getMessage().contains("cannot edit"), e.getMessage());
        }

        @Test
        @DisplayName("may share a location with a knowledge base the caller can also edit")
        void allowsACollisionWithEditOnBoth() throws Exception {
            otherKnowledgeBaseUsesTable("team_docs");
            when(accessGuard.hasAccess(OTHER, AccessLevel.EDIT)).thenReturn(true);

            assertDoesNotThrow(() -> guard.prepareNew(kb("beta", null, Map.of("table", "team_docs")), true));
        }

        /**
         * The neighbour that cannot be read may be the very one that owns the location,
         * so skipping it let an editor point a knowledge base at another's table on a
         * transient read error — the same hole the page budget already closes.
         */
        @Test
        @DisplayName("a knowledge base that cannot be read refuses the location rather than being skipped")
        void unreadableNeighbourFailsClosed() throws Exception {
            otherKnowledgeBaseUsesTable("team_docs");
            when(ragStore.read(OTHER, 2)).thenThrow(new IResourceStore.ResourceStoreException("connection reset"));

            var e = assertThrows(IllegalStateException.class,
                    () -> guard.prepareNew(kb("beta", null, Map.of("table", "team_docs")), true));
            assertTrue(e.getMessage().contains(OTHER), e.getMessage());
            verify(accessGuard, never()).hasAccess(anyString(), any());
        }

        @Test
        @DisplayName("a knowledge base whose document fails to load refuses the location too")
        void neighbourFailingWithARuntimeErrorFailsClosed() throws Exception {
            otherKnowledgeBaseUsesTable("team_docs");
            when(ragStore.read(OTHER, 2)).thenThrow(new IllegalArgumentException("cannot deserialize"));

            assertThrows(IllegalStateException.class, () -> guard.prepareNew(kb("beta", null, Map.of("table", "team_docs")), true));
        }

        @Test
        @DisplayName("a knowledge base deleted since it was listed owns no location and is skipped")
        void deletedNeighbourIsSkipped() throws Exception {
            otherKnowledgeBaseUsesTable("team_docs");
            when(ragStore.read(OTHER, 2)).thenThrow(new IResourceStore.ResourceNotFoundException("deleted"));

            assertDoesNotThrow(() -> guard.prepareNew(kb("beta", null, Map.of("table", "team_docs")), true));
        }
    }

    @Nested
    @DisplayName("an update")
    class Update {

        @Test
        @DisplayName("that omits storeNamespace keeps the stored one")
        void carriesTheNamespaceForward() {
            var updated = kb("alpha", null, null);
            guard.prepareUpdate(SELF, kb("alpha", "id", null), updated);
            assertEquals("id", updated.getStoreNamespace());

            var legacy = kb("alpha", null, null);
            guard.prepareUpdate(SELF, kb("alpha", null, null), legacy);
            assertNull(legacy.getStoreNamespace(), "a 6.5.0 knowledge base saved unchanged stays on its store");
        }

        @Test
        @DisplayName("cannot switch back to the name layout")
        void refusesIdToName() {
            assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, kb("alpha", "id", null), kb("alpha", "name", null)));
        }

        @Test
        @DisplayName("renaming a name-layout knowledge base moves it to a store of its own, not to the one the new name maps to")
        void legacyRenameMigrates() {
            var renamed = kb("victim", null, null);
            guard.prepareUpdate(SELF, kb("alpha", null, null), renamed);
            assertEquals("id", renamed.getStoreNamespace());
        }

        @Test
        @DisplayName("an explicit location that did not change is not checked again")
        void unchangedExplicitLocationIsNotRechecked() throws Exception {
            var updated = kb("alpha", "id", Map.of("table", "eddi_kb_pinned_before_the_rule"));
            assertDoesNotThrow(() -> guard.prepareUpdate(SELF, kb("alpha", "id", Map.of("table", "eddi_kb_pinned_before_the_rule")),
                    updated));
            verify(descriptorStore, never()).readDescriptors(anyString(), any(), any(), any(), anyBoolean());
        }

        @Test
        @DisplayName("an explicit location that changed to another knowledge base's is refused")
        void changedExplicitLocationIsChecked() throws Exception {
            otherKnowledgeBaseUsesTable("team_docs");
            when(accessGuard.hasAccess(OTHER, AccessLevel.EDIT)).thenReturn(false);

            assertThrows(BadRequestException.class,
                    () -> guard.prepareUpdate(SELF, kb("alpha", "id", Map.of("table", "mine")), kb("alpha", "id", Map.of("table", "team_docs"))));
        }

        @Test
        @DisplayName("its own explicit location is not a collision with itself")
        void selfIsNotACollision() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setResource(URI.create("eddi://ai.labs.rag/ragstore/rags/" + SELF + "?version=1"));
            when(descriptorStore.readDescriptors(eq("ai.labs.rag"), isNull(), eq(0), anyInt(), anyBoolean())).thenReturn(List.of(descriptor));
            when(ragStore.read(SELF, 1)).thenReturn(kb("alpha", "id", Map.of("table", "mine")));

            assertDoesNotThrow(() -> guard.prepareUpdate(SELF, kb("alpha", "id", null), kb("alpha", "id", Map.of("table", "mine"))));
        }
        @Test
        @DisplayName("a name-layout knowledge base that drops its explicit location moves to a store of its own")
        void droppingExplicitLocationMigrates() {
            // rename while keeping the explicit table, then drop the table: the second
            // step used to land, unchecked, on the default store of the NEW name
            var renamed = kb("victim", null, Map.of("table", "mine"));
            guard.prepareUpdate(SELF, kb("alpha", null, Map.of("table", "mine")), renamed);
            assertNull(renamed.getStoreNamespace());

            var dropped = kb("victim", null, null);
            guard.prepareUpdate(SELF, renamed, dropped);
            assertEquals("id", dropped.getStoreNamespace());
        }

        @Test
        @DisplayName("a name-layout knowledge base that changes backend or server moves to a store of its own")
        void changingBackendOrServerMigrates() {
            // the same name maps to the new backend's (or server's) default store, which
            // can be another 6.5.0 knowledge base's of that name
            var previous = kb("alpha", null, Map.of("host", "pg-a"));
            previous.setStoreType("pgvector");
            var otherBackend = kb("alpha", null, Map.of("host", "pg-a"));
            otherBackend.setStoreType("qdrant");
            guard.prepareUpdate(SELF, previous, otherBackend);
            assertEquals("id", otherBackend.getStoreNamespace());

            var otherServer = kb("alpha", null, Map.of("host", "pg-b"));
            otherServer.setStoreType("pgvector");
            guard.prepareUpdate(SELF, previous, otherServer);
            assertEquals("id", otherServer.getStoreNamespace());

            // a parameter that does not choose the store leaves the layout alone
            var tuned = kb("alpha", null, Map.of("host", "pg-a", "dimension", "768"));
            tuned.setStoreType("pgvector");
            guard.prepareUpdate(SELF, previous, tuned);
            assertNull(tuned.getStoreNamespace());
        }

        @Test
        @DisplayName("spelling out a server parameter's default is not a move: the factory already connected there")
        void writingOutTheDefaultServerDoesNotMigrate() {
            var previous = kb("alpha", null, null);
            var explicitDefaults = kb("alpha", null, Map.of("host", "localhost", "port", "5432", "database", "eddi"));
            guard.prepareUpdate(SELF, previous, explicitDefaults);
            assertNull(explicitDefaults.getStoreNamespace(), "an omitted host is localhost; its documents must stay reachable");
        }

        @Test
        @DisplayName("an Elasticsearch index expression (wildcard, list, exclusion) is refused")
        void elasticsearchIndexExpressionRefused() {
            for (String index : List.of("*", "kb_a,kb_b", "-kb", "kb*", "remote:kb", "Kb", " team-docs", "team-docs ")) {
                var config = kb("alpha", "id", Map.of("indexName", index));
                config.setStoreType("elasticsearch");
                var previous = kb("alpha", "id", Map.of("indexName", "mine"));
                previous.setStoreType("elasticsearch");
                assertThrows(BadRequestException.class, () -> guard.prepareUpdate(SELF, previous, config), index);
            }
            var plain = kb("alpha", "id", Map.of("indexName", "team-docs_1"));
            plain.setStoreType("elasticsearch");
            assertDoesNotThrow(() -> guard.prepareNew(plain, true));
        }

        @Test
        @DisplayName("a collision scan that runs out of pages refuses the location rather than passing it")
        void exhaustedCollisionScanFailsClosed() throws Exception {
            List<DocumentDescriptor> fullPage = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                var descriptor = new DocumentDescriptor();
                descriptor.setResource(URI.create("eddi://ai.labs.rag/ragstore/rags/" + OTHER + "?version=2"));
                fullPage.add(descriptor);
            }
            when(descriptorStore.readDescriptors(eq("ai.labs.rag"), isNull(), anyInt(), anyInt(), anyBoolean())).thenReturn(fullPage);
            when(ragStore.read(OTHER, 2)).thenReturn(kb("other", "id", null));

            assertThrows(BadRequestException.class,
                    () -> guard.prepareUpdate(SELF, kb("alpha", "id", Map.of("table", "mine")), kb("alpha", "id", Map.of("table", "other_table"))));
        }
    }
}
