/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rag;

import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.ResolvedCredentials;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** What the embedding key reference resolves to; runtime-built. */
    private static final String EMBEDDING_PLAINTEXT = "embed-" + "e".repeat(16);

    /** A key shape the pattern redaction recognises, built at runtime. */
    private static final String SHAPED_KEY = "sk-proj-" + "a".repeat(32);

    private KnowledgeBaseFailureRedactor redactor;

    @BeforeEach
    void setUp() {
        SecretResolver secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> {
            String value = inv.getArgument(0);
            return switch (value) {
                case "${vault:store-password}" -> PLAINTEXT;
                case "${vault:embedding-key}" -> EMBEDDING_PLAINTEXT;
                // An unknown reference stays in place, as the resolver leaves one it
                // cannot resolve.
                default -> value;
            };
        });
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        redactor = new KnowledgeBaseFailureRedactor(secretResolver, globalVariableResolver);
    }

    private static RagConfiguration knowledgeBase() {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("apiKey", "${vault:embedding-key}"));
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
    @DisplayName("an unlabelled short password in the failure text withholds the provider's text; without it the text is unchanged")
    void shortPasswordWithholdsTheText() {
        String shortPassword = "w".repeat(7);
        var config = knowledgeBase();
        config.setStoreType("pgvector");
        config.setStoreParameters(Map.of("user", "eddi", "password", shortPassword));
        var failure = new IllegalStateException("connection rejected for " + shortPassword + " on host db");

        var redacted = redactor.redactFailure(config, failure);

        assertTrue(redacted.altered());
        assertFalse(redacted.text().contains(shortPassword), redacted.text());
        assertTrue(redacted.text().contains("IllegalStateException"), redacted.text());
        // A short user name, an identity rather than a secret, withholds nothing.
        var identityOnly = knowledgeBase();
        identityOnly.setStoreType("pgvector");
        identityOnly.setStoreParameters(Map.of("user", "eddi", "password", "${vault:store-password}"));
        var unrelated = redactor.redactFailure(identityOnly, new IllegalStateException("database eddi does not exist"));
        assertFalse(unrelated.altered());
        assertEquals("database eddi does not exist", unrelated.text());
    }

    @Test
    @DisplayName("a 3-character password, and a 5-character one joined to an alphanumeric run, both withhold the provider's text")
    void anyShortCredentialWithholdsEveryFailure() {
        for (String password : List.of("x".repeat(3), "y".repeat(5))) {
            var config = knowledgeBase();
            config.setStoreType("pgvector");
            config.setStoreParameters(Map.of("password", password));
            // Echoed without a label, joined to other characters, or not echoed at all:
            // the provider's text is never returned for this configuration.
            for (String message : List.of("auth failed abc" + password + "123", "connection reset by peer")) {
                var redacted = redactor.redactFailure(config, new IllegalStateException(message));
                assertTrue(redacted.altered());
                assertFalse(redacted.text().contains(password), redacted.text());
                assertTrue(redacted.text().contains("IllegalStateException"), redacted.text());
            }
        }
    }

    @Test
    @DisplayName("a null top-level message does not bypass the short-credential fail-safe; the log text names only classes")
    void nullMessageWithAShortPasswordInTheCause() {
        String shortPassword = "v".repeat(5);
        var config = knowledgeBase();
        config.setStoreType("pgvector");
        config.setStoreParameters(Map.of("password", shortPassword));
        String causeMessage = "password authentication failed: " + shortPassword;
        var failure = new RuntimeException((String) null, new IllegalStateException(causeMessage));

        var redacted = redactor.redactFailure(config, failure);

        assertTrue(redacted.altered(), "the caller must not log the exception object");
        assertFalse(redacted.text().contains(shortPassword), redacted.text());
        assertTrue(redacted.text().contains("RuntimeException"), redacted.text());
        assertFalse(redacted.logText().contains(shortPassword), redacted.logText());
        assertFalse(redacted.logText().contains("password authentication failed"), redacted.logText());
        assertTrue(redacted.logText().contains(IllegalStateException.class.getName()), redacted.logText());
    }

    @Test
    @DisplayName("a secret in a cause, under a null top-level message, marks the failure altered and is redacted in the log text")
    void secretInACauseIsRedactedInTheLogText() {
        var config = knowledgeBase();
        config.setStoreType("pgvector");
        var failure = new RuntimeException((String) null, new IllegalStateException("authentication failed for " + PLAINTEXT + " on host db"));

        var redacted = redactor.redactFailure(config, failure);

        assertTrue(redacted.altered(), "the caller must not log the exception object");
        assertNull(redacted.text());
        assertFalse(redacted.logText().contains(PLAINTEXT), redacted.logText());
        assertTrue(redacted.logText().contains("on host db"), redacted.logText());
        // Nothing to remove anywhere in the chain: not altered, the exception may be
        // logged.
        var clean = redactor.redactFailure(config, new RuntimeException((String) null, new IllegalStateException("connection refused")));
        assertFalse(clean.altered());
    }

    @Test
    @DisplayName("a credential in cause #17, beyond the inspected depth, never reaches the log text, which says causes were left out")
    void credentialBeyondTheDepthCapIsNotLogged() {
        Throwable failure = new IllegalStateException("authentication failed for " + PLAINTEXT);
        for (int i = 0; i < KnowledgeBaseFailureRedactor.MAX_CAUSE_DEPTH; i++) {
            failure = new RuntimeException("wrapped " + i, failure);
        }

        var redacted = redactor.redactFailure(knowledgeBase(), failure);

        assertFalse(redacted.logText().contains(PLAINTEXT), redacted.logText());
        assertFalse(redacted.logText().contains("authentication failed"), redacted.logText());
        assertTrue(redacted.logText().contains("further causes not inspected"), redacted.logText());
    }

    @Test
    @DisplayName("a credential in a suppressed exception never reaches the log text, which counts the suppressed exception")
    void credentialInASuppressedExceptionIsNotLogged() {
        var failure = new IllegalStateException("store write failed");
        failure.addSuppressed(new IllegalStateException("close failed for " + PLAINTEXT));

        var redacted = redactor.redactFailure(knowledgeBase(), failure);

        assertFalse(redacted.logText().contains(PLAINTEXT), redacted.logText());
        assertFalse(redacted.logText().contains("close failed"), redacted.logText());
        assertTrue(redacted.logText().contains("1 suppressed exception(s) not inspected"), redacted.logText());
        assertEquals("store write failed", redacted.text());
    }

    @Test
    @DisplayName("a vault reference that cannot be resolved now withholds the provider's text")
    void unresolvableReferenceWithholdsTheText() {
        var config = knowledgeBase();
        config.setStoreParameters(Map.of("password", "${vault:rotated-away}"));

        var redacted = redactor.redactFailure(config, new IllegalStateException("authentication failed for some-old-value"));

        assertTrue(redacted.altered());
        assertTrue(redacted.text().contains("cannot be resolved now"), redacted.text());
        assertFalse(redacted.logText().contains("some-old-value"), redacted.logText());
    }

    @Test
    @DisplayName("a value a live client was built with before a rotation is removed, though the reference now resolves to another")
    void preRotationValueHeldByALiveClientIsRemoved() {
        String rotatedAway = "before-rotation-" + "r".repeat(12);
        var credentials = new ResolvedCredentials();
        Object liveClient = new Object();
        credentials.record(liveClient, List.of(rotatedAway));
        redactor.resolvedCredentials = credentials;

        var redacted = redactor.redactFailure(knowledgeBase(), new IllegalStateException("rejected " + rotatedAway + " on host db"));

        assertFalse(redacted.text().contains(rotatedAway), redacted.text());
        assertFalse(redacted.logText().contains(rotatedAway), redacted.logText());
        assertTrue(redacted.text().contains("on host db"), redacted.text());
        assertNotNull(liveClient);
    }

    @Test
    @DisplayName("long and reference credentials only: the scrubbed provider text is returned")
    void longCredentialsKeepTheProviderText() {
        var config = knowledgeBase();
        config.setStoreType("pgvector");
        config.setStoreParameters(Map.of("password", "${vault:store-password}"));

        var redacted = redactor.redactFailure(config, new IllegalStateException("authentication failed for " + PLAINTEXT + " on host db"));

        assertFalse(redacted.text().contains(PLAINTEXT), redacted.text());
        assertTrue(redacted.text().contains("on host db"), redacted.text());
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
