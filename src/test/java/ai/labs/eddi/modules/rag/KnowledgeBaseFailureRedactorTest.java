/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The second layer of the 2026-11 finding 1 on the RAG path: a provider error
 * returned by RAG search, or stored as the ingestion status, has the knowledge
 * base's secrets removed by value.
 */
@DisplayName("KnowledgeBaseFailureRedactor")
class KnowledgeBaseFailureRedactorTest {

    /**
     * Built at runtime and low-entropy: no credential-shaped literal in the source.
     */
    private static final String PLAINTEXT = "store-" + "p".repeat(16);

    /** A key shape the pattern redaction recognises, built at runtime. */
    private static final String SHAPED_KEY = "sk-proj-" + "a".repeat(32);

    private KnowledgeBaseFailureRedactor redactor;

    @BeforeEach
    void setUp() {
        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return "${vault:store-password}".equals(value) ? PLAINTEXT : value;
        });
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        redactor = new KnowledgeBaseFailureRedactor(secretResolver, globalVariableResolver);
    }

    private static RagConfiguration knowledgeBase() {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("apiKey", "k"));
        config.setStoreParameters(Map.of("password", "${vault:store-password}"));
        return config;
    }

    @Test
    @DisplayName("a secret the provider echoes is removed by value")
    void echoedSecretIsRemoved() {
        String redacted = redactor.redact(knowledgeBase(), "authentication failed for password '" + PLAINTEXT + "'");

        assertFalse(redacted.contains(PLAINTEXT), redacted);
        assertTrue(redacted.startsWith("authentication failed"), redacted);
    }

    @Test
    @DisplayName("an unlabelled literal credential is removed; non-credential literals are not")
    void literalCredentialIsRemoved() {
        String literalKey = "literal" + "k".repeat(12);
        var config = knowledgeBase();
        config.setEmbeddingParameters(Map.of("apiKey", literalKey, "model", "text-embedding-model-large"));

        String redacted = redactor.redact(config, "rejected " + literalKey + " for text-embedding-model-large");

        assertFalse(redacted.contains(literalKey), redacted);
        assertTrue(redacted.contains("text-embedding-model-large"), redacted);
    }

    @Test
    @DisplayName("a message without secrets is kept")
    void plainMessageIsKept() {
        assertEquals("connection refused", redactor.redact(knowledgeBase(), "connection refused"));
        assertNull(redactor.redact(knowledgeBase(), null));
    }

    @Test
    @DisplayName("without the bean, credential shapes are still redacted")
    void fallbackRedactsShapes() {
        String redacted = KnowledgeBaseFailureRedactor.redact(null, knowledgeBase(),
                "Incorrect API key provided: " + SHAPED_KEY);

        assertFalse(redacted.contains(SHAPED_KEY), redacted);
    }
}
