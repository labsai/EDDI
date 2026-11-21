/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.documents.index;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.documents.index.DocumentIndexEligibility.Reference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DocumentIndexEligibility — the one rule for document indexes")
class DocumentIndexEligibilityTest {

    static final String RAG_ID = "65f0aa11bb22cc33dd44ee77";

    /** An eligible document index on pgvector, per-id layout, no sources. */
    static RagConfiguration eligible() {
        var config = new RagConfiguration();
        config.setName("documents");
        config.setStoreType("pgvector");
        config.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_ID);
        config.setDocumentIndex(true);
        return config;
    }

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        @Test
        void eligibleIndexHasNoReason() {
            assertNull(DocumentIndexEligibility.ineligibility(eligible()));
            assertTrue(DocumentIndexEligibility.isEligible(eligible()));
        }

        @Test
        void unflaggedOrAbsentConfigurationIsNotEligible() {
            var config = eligible();
            config.setDocumentIndex(null);
            assertFalse(DocumentIndexEligibility.isDocumentIndex(config));
            assertTrue(DocumentIndexEligibility.ineligibility(config).contains("documentIndex"));
            config.setDocumentIndex(false);
            assertFalse(DocumentIndexEligibility.isEligible(config));
            assertNotNull(DocumentIndexEligibility.ineligibility(null));
        }

        @Test
        void nameLayoutIsNotEligible() {
            var config = eligible();
            config.setStoreNamespace(KnowledgeBaseStorage.NAMESPACE_NAME);
            assertTrue(DocumentIndexEligibility.ineligibility(config).contains("storeNamespace"));
            config.setStoreNamespace(null);
            assertFalse(DocumentIndexEligibility.isEligible(config));
        }

        @Test
        void atlasDefaultCollectionIsNotEligibleButAnExplicitOneIs() {
            var config = eligible();
            config.setStoreType("mongodb-atlas");
            assertTrue(DocumentIndexEligibility.ineligibility(config).contains("Atlas"));

            config.setStoreParameters(new HashMap<>(Map.of("collectionName", "document_chunks")));
            assertNull(DocumentIndexEligibility.ineligibility(config));
        }

        @ParameterizedTest
        @ValueSource(strings = {"in-memory", "pgvector", "elasticsearch", "qdrant", "chroma"})
        void storesThatFilterByMetadataAreEligible(String storeType) {
            var config = eligible();
            config.setStoreType(storeType);
            assertNull(DocumentIndexEligibility.ineligibility(config));
        }

        @Test
        void unknownStoreTypeIsNotEligible() {
            var config = eligible();
            config.setStoreType("weaviate");
            assertTrue(DocumentIndexEligibility.ineligibility(config).contains("filter by metadata"));
            config.setStoreType(null);
            assertFalse(DocumentIndexEligibility.isEligible(config));
        }

        @Test
        void ingestionSourcesMakeItIneligible() {
            var config = eligible();
            var source = new IngestionSource();
            source.setName("crawler");
            config.setSources(List.of(source));
            assertTrue(DocumentIndexEligibility.ineligibility(config).contains("sources"));
        }
    }

    @Nested
    @DisplayName("references")
    class References {

        @Test
        void uriWithVersion() {
            assertEquals(new Reference(RAG_ID, 3),
                    DocumentIndexEligibility.parseReference("eddi://ai.labs.rag/ragstore/rags/" + RAG_ID + "?version=3"));
        }

        @Test
        void uriWithoutVersionNamesTheCurrentOne() {
            assertEquals(new Reference(RAG_ID, null), DocumentIndexEligibility.parseReference("eddi://ai.labs.rag/ragstore/rags/" + RAG_ID));
        }

        @Test
        void bareId() {
            assertEquals(new Reference(RAG_ID, null), DocumentIndexEligibility.parseReference("  " + RAG_ID + " "));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "  ", "not-an-id", "eddi://ai.labs.llm/llmstore/llms/65f0aa11bb22cc33dd44ee77",
                "eddi://ai.labs.rag/ragstore/rags/"})
        void malformedReferencesAreRefused(String reference) {
            assertThrows(IllegalArgumentException.class, () -> DocumentIndexEligibility.parseReference(reference));
        }

        @Test
        void referenceToAnEligibleIndexHasNoReason() {
            assertNull(DocumentIndexEligibility.referenceIneligibility(RAG_ID, ref -> RAG_ID.equals(ref.id()) ? eligible() : null));
        }

        @Test
        void referenceToAMissingConfigurationIsRefused() {
            assertTrue(DocumentIndexEligibility.referenceIneligibility(RAG_ID, ref -> null).contains("does not exist"));
        }

        @Test
        void referenceToAnOrdinaryKnowledgeBaseIsRefused() {
            var ordinary = eligible();
            ordinary.setDocumentIndex(null);
            assertNotNull(DocumentIndexEligibility.referenceIneligibility(RAG_ID, ref -> ordinary));
        }

        @Test
        void malformedReferenceIsAReasonNotAThrow() {
            assertNotNull(DocumentIndexEligibility.referenceIneligibility("nope", ref -> eligible()));
        }
    }

    @Nested
    @DisplayName("chunk tag")
    class ChunkTag {

        @Test
        void docLibKbIdIsPrefixedAndNeverTheOrdinaryTag() {
            assertEquals("doclib:" + RAG_ID, KnowledgeBaseStorage.docLibKbId(RAG_ID));
            assertNotEquals(KnowledgeBaseStorage.chunkKbId(RAG_ID, eligible()), KnowledgeBaseStorage.docLibKbId(RAG_ID));
        }

        @Test
        void docLibKbIdNeedsAnId() {
            assertThrows(IllegalArgumentException.class, () -> KnowledgeBaseStorage.docLibKbId(" "));
            assertThrows(IllegalArgumentException.class, () -> KnowledgeBaseStorage.docLibKbId(null));
        }
    }
}
