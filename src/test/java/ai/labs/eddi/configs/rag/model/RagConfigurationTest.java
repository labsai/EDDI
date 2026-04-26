/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RagConfigurationTest {

    @Test
    void defaults() {
        var config = new RagConfiguration();
        assertNull(config.getName());
        assertEquals("openai", config.getEmbeddingProvider());
        assertNull(config.getEmbeddingParameters());
        assertEquals("in-memory", config.getStoreType());
        assertNull(config.getStoreParameters());
        assertEquals("recursive", config.getChunkStrategy());
        assertEquals(512, config.getChunkSize());
        assertEquals(64, config.getChunkOverlap());
        assertEquals(5, config.getMaxResults());
        assertEquals(0.6, config.getMinScore());
    }

    @Test
    void settersAndGetters() {
        var config = new RagConfiguration();
        config.setName("Product Knowledge Base");
        config.setEmbeddingProvider("azure-openai");
        config.setEmbeddingParameters(Map.of(
                "endpoint", "https://my.openai.azure.com/",
                "deploymentName", "text-embedding-3-small"));
        config.setStoreType("pgvector");
        config.setStoreParameters(Map.of(
                "host", "localhost",
                "database", "eddi_rag"));
        config.setChunkStrategy("paragraph");
        config.setChunkSize(1024);
        config.setChunkOverlap(128);
        config.setMaxResults(10);
        config.setMinScore(0.75);

        assertEquals("Product Knowledge Base", config.getName());
        assertEquals("azure-openai", config.getEmbeddingProvider());
        assertEquals(2, config.getEmbeddingParameters().size());
        assertEquals("pgvector", config.getStoreType());
        assertEquals(2, config.getStoreParameters().size());
        assertEquals("paragraph", config.getChunkStrategy());
        assertEquals(1024, config.getChunkSize());
        assertEquals(128, config.getChunkOverlap());
        assertEquals(10, config.getMaxResults());
        assertEquals(0.75, config.getMinScore());
    }

    @Test
    void jacksonRoundTrip() throws Exception {
        var config = new RagConfiguration();
        config.setName("Test KB");
        config.setEmbeddingProvider("ollama");
        config.setStoreType("qdrant");
        config.setChunkSize(256);
        config.setMaxResults(3);
        config.setMinScore(0.8);

        var mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(config);
        var deserialized = mapper.readValue(json, RagConfiguration.class);

        assertEquals("Test KB", deserialized.getName());
        assertEquals("ollama", deserialized.getEmbeddingProvider());
        assertEquals("qdrant", deserialized.getStoreType());
        assertEquals(256, deserialized.getChunkSize());
        assertEquals(3, deserialized.getMaxResults());
        assertEquals(0.8, deserialized.getMinScore());
    }
}
