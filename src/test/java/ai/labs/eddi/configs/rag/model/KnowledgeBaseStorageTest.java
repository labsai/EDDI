/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("KnowledgeBaseStorage — where a knowledge base's vectors live")
class KnowledgeBaseStorageTest {

    private static final String ID_A = "65f0aa11bb22cc33dd44ee55";
    private static final String ID_B = "65f0aa11bb22cc33dd44ee66";

    private static RagConfiguration kb(String name, String storeType, String namespace) {
        var config = new RagConfiguration();
        config.setName(name);
        config.setStoreType(storeType);
        config.setStoreNamespace(namespace);
        return config;
    }

    @Nested
    @DisplayName("the 6.5.0 name layout is reproduced exactly, so upgraded knowledge bases keep their data")
    class Legacy {

        /**
         * The expected values are what 6.5.0's EmbeddingStoreFactory produced for the
         * same name — each store type sanitised differently, and Atlas not at all.
         */
        @ParameterizedTest
        @CsvSource({
                "pgvector,      Product Docs!, eddi_kb_product_docs_",
                "mongodb-atlas, Product Docs!, eddi_kb_Product Docs!",
                "elasticsearch, Product Docs!, eddi_kb_product_docs_",
                "qdrant,        Product Docs!, eddi_kb_product_docs",
                "chroma,        Product Docs!, eddi_kb_product_docs"
        })
        void defaultNamesMatch650(String storeType, String name, String expected) {
            assertEquals(expected, KnowledgeBaseStorage.physicalName(ID_A, kb(name, storeType, null)));
            assertEquals(expected, KnowledgeBaseStorage.physicalName(ID_A, kb(name, storeType, "name")));
        }

        @Test
        void pgvectorNameIsTruncatedTo63() {
            String name = "x".repeat(100);
            assertEquals(63, KnowledgeBaseStorage.physicalName(ID_A, kb(name, "pgvector", null)).length());
        }

        @Test
        @DisplayName("chunks are tagged with the name and retrieval is not filtered — 6.5.0 chunks carry no id")
        void tagsWithTheNameAndDoesNotFilter() {
            var config = kb("alpha", "pgvector", null);
            assertEquals("alpha", KnowledgeBaseStorage.chunkKbId(ID_A, config));
            assertFalse(KnowledgeBaseStorage.filtersRetrieval(config));
        }

        @Test
        void aNamelessLegacyKnowledgeBaseCannotBeAddressed() {
            assertThrows(IllegalArgumentException.class, () -> KnowledgeBaseStorage.physicalName(ID_A, kb(" ", "pgvector", null)));
            assertThrows(IllegalArgumentException.class, () -> KnowledgeBaseStorage.chunkKbId(ID_A, kb(null, "in-memory", null)));
        }
    }

    @Nested
    @DisplayName("the per-id layout")
    class ById {

        @Test
        @DisplayName("two knowledge bases with one name get two locations")
        void sameNameDifferentIdsDoNotCollide() {
            String a = KnowledgeBaseStorage.physicalName(ID_A, kb("alpha", "pgvector", "id"));
            String b = KnowledgeBaseStorage.physicalName(ID_B, kb("alpha", "pgvector", "id"));

            assertNotEquals(a, b);
            assertEquals("eddi_kbid_" + ID_A, a);
        }

        @Test
        @DisplayName("an id-layout location can never equal a name-layout one, whatever the name")
        void idLocationsCannotCollideWithLegacyOnes() {
            // A legacy knowledge base named like an id-layout location still maps under
            // "eddi_kb_", never under "eddi_kbid_".
            String legacy = KnowledgeBaseStorage.physicalName(ID_B, kb("id_" + ID_A, "pgvector", null));
            assertNotEquals(KnowledgeBaseStorage.physicalName(ID_A, kb("x", "pgvector", "id")), legacy);
        }

        @Test
        @DisplayName("renaming does not move the store")
        void renamingKeepsTheLocation() {
            assertEquals(KnowledgeBaseStorage.locationKey(ID_A, kb("alpha", "qdrant", "id")),
                    KnowledgeBaseStorage.locationKey(ID_A, kb("renamed", "qdrant", "id")));
            assertNotEquals(KnowledgeBaseStorage.locationKey(ID_A, kb("alpha", "qdrant", null)),
                    KnowledgeBaseStorage.locationKey(ID_A, kb("renamed", "qdrant", null)));
        }

        @Test
        void tagsWithTheIdAndFilters() {
            var config = kb("alpha", "in-memory", "id");
            assertEquals(ID_A, KnowledgeBaseStorage.chunkKbId(ID_A, config));
            assertTrue(KnowledgeBaseStorage.filtersRetrieval(config));
            assertNull(KnowledgeBaseStorage.physicalName(ID_A, config), "in-memory has no physical name");
        }

        @Test
        void requiresAnId() {
            assertThrows(IllegalArgumentException.class, () -> KnowledgeBaseStorage.chunkKbId(null, kb("a", "pgvector", "id")));
        }
    }

    @Nested
    @DisplayName("explicit locations")
    class Explicit {

        @Test
        void explicitNameWins() {
            var config = kb("alpha", "pgvector", "id");
            config.setStoreParameters(Map.of("table", "docs"));
            assertEquals("docs", KnowledgeBaseStorage.physicalName(ID_A, config));
            assertEquals("docs", KnowledgeBaseStorage.explicitPhysicalName(config));
        }

        @Test
        @DisplayName("the parameter that names the location differs by store")
        void parameterPerStore() {
            assertEquals("table", KnowledgeBaseStorage.physicalNameParameter("pgvector"));
            assertEquals("collectionName", KnowledgeBaseStorage.physicalNameParameter("mongodb-atlas"));
            assertEquals("indexName", KnowledgeBaseStorage.physicalNameParameter("elasticsearch"));
            assertNull(KnowledgeBaseStorage.physicalNameParameter("in-memory"));
        }

        @Test
        void reservedPrefix() {
            assertTrue(KnowledgeBaseStorage.isReservedName("eddi_kb_alpha"));
            assertTrue(KnowledgeBaseStorage.isReservedName(" EDDI_KBID_x"));
            assertFalse(KnowledgeBaseStorage.isReservedName("team_docs"));
        }
    }
}
