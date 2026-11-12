/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Before this, any {@code eddi-editor} could save an LLM config that spent the
 * server's own cloud identity (Bedrock, Vertex, OCI), made Jlama download any
 * model to any writable path, or wrote every prompt and response — other users'
 * conversations included — to the server log.
 */
@DisplayName("LlmProviderAuthorPolicy")
class LlmProviderAuthorPolicyTest {

    private SecurityIdentity editor;
    private SecurityIdentity admin;

    @BeforeEach
    void setUp() {
        editor = mock(SecurityIdentity.class);
        when(editor.hasRole("eddi-editor")).thenReturn(true);
        admin = mock(SecurityIdentity.class);
        when(admin.hasRole("eddi-admin")).thenReturn(true);
    }

    private static LlmProviderAuthorPolicy policy(SecurityIdentity identity, boolean authorization, List<String> restricted,
                                                  boolean loggingAllowed, String jlamaDir) {
        return new LlmProviderAuthorPolicy(identity, mock(IJsonSerialization.class), authorization, restricted, List.of("bedrock", "vertex"),
                loggingAllowed, Optional.ofNullable(jlamaDir));
    }

    private LlmProviderAuthorPolicy asEditor() {
        return policy(editor, true, List.of("bedrock", "gemini-vertex", "oracle-genai", "jlama"), false, null);
    }

    private static String task(String type, String parameters) {
        return "{\"tasks\":[{\"id\":\"t1\",\"type\":\"" + type + "\",\"parameters\":{" + parameters + "}}]}";
    }

    @Nested
    @DisplayName("restricted providers")
    class Restricted {

        @Test
        @DisplayName("an editor may not create a Bedrock config — it spends the server's AWS identity")
        void editorRefused() {
            var e = assertThrows(ForbiddenException.class,
                    () -> asEditor().requireMayAuthor(task("bedrock", "\"modelId\":\"big-model\",\"region\":\"us-east-1\""), null, "langchain"));
            assertTrue(e.getMessage().contains("bedrock"), e.getMessage());
        }

        @Test
        @DisplayName("Vertex, OCI and Jlama are restricted too; a provider with its own key is not")
        void otherProviders() {
            var policy = asEditor();
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task("gemini-vertex", "\"project\":\"p\""), null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task("oracle-genai", "\"profile\":\"DEFAULT\""), null, "langchain"));
            assertThrows(ForbiddenException.class,
                    () -> policy.requireMayAuthor(task("jlama", "\"modelName\":\"some/huge-model\""), null, "langchain"));
            assertDoesNotThrow(() -> policy.requireMayAuthor(task("openai", "\"modelName\":\"gpt-4o\""), null, "langchain"));
        }

        @Test
        @DisplayName("an administrator may, and so may anyone with authorization off")
        void adminAllowed() {
            String bedrock = task("bedrock", "\"modelId\":\"big-model\"");
            assertDoesNotThrow(() -> policy(admin, true, List.of("bedrock"), false, null).requireMayAuthor(bedrock, null, "langchain"));
            assertDoesNotThrow(() -> policy(editor, false, List.of("bedrock"), false, null).requireMayAuthor(bedrock, null, "langchain"));
        }

        @Test
        @DisplayName("an editor may edit the prompt of an administrator's Bedrock config, not re-point it")
        void carryOver() {
            String stored = task("bedrock", "\"modelId\":\"m1\",\"region\":\"eu-west-1\",\"systemMessage\":\"old\"");
            var policy = asEditor();

            assertDoesNotThrow(() -> policy.requireMayAuthor(task("bedrock", "\"modelId\":\"m1\",\"region\":\"eu-west-1\",\"systemMessage\":\"new\""),
                    stored, "langchain"));
            assertThrows(ForbiddenException.class,
                    () -> policy.requireMayAuthor(task("bedrock", "\"modelId\":\"m2\",\"region\":\"eu-west-1\",\"systemMessage\":\"old\""), stored,
                            "langchain"));
            assertThrows(ForbiddenException.class,
                    () -> policy.requireMayAuthor(task("bedrock", "\"modelId\":\"m1\",\"region\":\"us-east-1\",\"systemMessage\":\"old\""), stored,
                            "langchain"));
        }

        @Test
        @DisplayName("switching a task to a restricted provider is refused even when the document existed")
        void switchRefused() {
            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(task("bedrock", "\"modelId\":\"m1\""),
                    task("openai", "\"modelName\":\"gpt-4o\""), "langchain"));
        }

        @Test
        @DisplayName("a provider named anywhere counts — a guardrail's, a cascade step's, a summary's")
        void nestedProviders() {
            var policy = asEditor();
            String guardrail = "{\"tasks\":[{\"type\":\"openai\",\"parameters\":{},\"inputGuardrails\":[{\"type\":\"classifier\","
                    + "\"parameters\":{\"provider\":\"bedrock\",\"modelId\":\"x\"}}]}]}";
            String cascade = "{\"tasks\":[{\"type\":\"openai\",\"modelCascade\":{\"steps\":[{\"type\":\"oracle-genai\",\"parameters\":{}}]}}]}";
            String summary = "{\"tasks\":[{\"type\":\"openai\",\"conversationSummary\":{\"llmProvider\":\"gemini-vertex\",\"llmModel\":\"x\"}}]}";
            String group = "{\"contextWindow\":{\"enabled\":true,\"llmProvider\":\"Bedrock\",\"llmModel\":\"x\"}}";
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(guardrail, null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(cascade, null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(summary, null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(group, null, "group"));
        }

        @Test
        @DisplayName("embedding providers with ambient credentials are restricted in a knowledge base")
        void embeddingProviders() {
            var policy = asEditor();
            assertThrows(ForbiddenException.class,
                    () -> policy.requireMayAuthor("{\"embeddingProvider\":\"bedrock\",\"embeddingParameters\":{\"region\":\"x\"}}", null, "rag"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor("{\"embeddingProvider\":\"vertex\"}", null, "rag"));
            assertDoesNotThrow(() -> policy.requireMayAuthor("{\"embeddingProvider\":\"openai\"}", null, "rag"));
        }

        @Test
        @DisplayName("the list is the operator's: an empty one restricts nothing")
        void operatorList() {
            assertDoesNotThrow(() -> policy(editor, true, List.of(), false, "base").requireMayAuthor(task("bedrock", "\"modelId\":\"m\""), null,
                    "langchain"));
        }
    }

    @Nested
    @DisplayName("content logging")
    class ContentLogging {

        @Test
        @DisplayName("an editor may not turn on request or response logging")
        void refused() {
            var policy = asEditor();
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task("openai", "\"logRequests\":\"true\""), null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task("anthropic", "\"logResponses\":\"TRUE\""), null, "langchain"));
            assertThrows(ForbiddenException.class,
                    () -> policy.requireMayAuthor(task("gemini", "\"logRequestsAndResponses\":\"{properties.debug}\""), null, "langchain"));
            assertDoesNotThrow(
                    () -> policy.requireMayAuthor(task("openai", "\"logRequests\":\"false\",\"logResponses\":\"false\""), null, "langchain"));
        }

        @Test
        @DisplayName("an unchanged flag an administrator set stays editable around; the operator may allow editors")
        void carryOverAndOptIn() {
            String stored = task("openai", "\"logRequests\":\"true\",\"systemMessage\":\"a\"");
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(task("openai", "\"logRequests\":\"true\",\"systemMessage\":\"b\""), stored,
                    "langchain"));
            assertDoesNotThrow(() -> policy(editor, true, List.of(), true, null).requireMayAuthor(task("openai", "\"logRequests\":\"true\""), null,
                    "langchain"));
        }
    }

    @Nested
    @DisplayName("Jlama paths")
    class JlamaPaths {

        private final Path base = Path.of("jlama-base-dir").toAbsolutePath().normalize();

        /** Jlama taken off the restricted list, so only the path rule speaks. */
        private LlmProviderAuthorPolicy editorWithBase(String dir) {
            return policy(editor, true, List.of("bedrock"), false, dir);
        }

        private String jlama(String key, String value) {
            return task("jlama", "\"" + key + "\":" + "\"" + value.replace("\\", "\\\\") + "\"");
        }

        @Test
        @DisplayName("without a base directory only an administrator may set a path")
        void noBase() {
            assertThrows(ForbiddenException.class, () -> editorWithBase(null).requireMayAuthor(jlama("modelCachePath", "/var/models"), null,
                    "langchain"));
            assertDoesNotThrow(() -> editorWithBase(null).requireMayAuthor(task("jlama", "\"modelName\":\"m\""), null, "langchain"));
        }

        @Test
        @DisplayName("with a base directory a path under it is accepted, relative or absolute")
        void inside() {
            var policy = editorWithBase(base.toString());
            assertDoesNotThrow(() -> policy.requireMayAuthor(jlama("modelCachePath", "models/a"), null, "langchain"));
            assertDoesNotThrow(() -> policy.requireMayAuthor(jlama("workingDirectory", base.resolve("work").toString()), null, "langchain"));
        }

        @Test
        @DisplayName("traversal and absolute paths outside the base are refused")
        void outside() {
            var policy = editorWithBase(base.toString());
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(jlama("modelCachePath", "../escape"), null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(jlama("modelCachePath", "models/../../escape"), null, "langchain"));
            assertThrows(ForbiddenException.class,
                    () -> policy.requireMayAuthor(jlama("workingDirectory", base.getParent().resolve("elsewhere").toString()), null, "langchain"));
            // A sibling whose name starts with the base's is not under it.
            assertThrows(ForbiddenException.class,
                    () -> policy.requireMayAuthor(jlama("modelCachePath", base.getParent().resolve(base.getFileName() + "-evil").toString()), null,
                            "langchain"));
        }

        @Test
        @DisplayName("an unchanged path an administrator set is carried over")
        void carryOver() {
            String stored = jlama("modelCachePath", "/var/models");
            assertDoesNotThrow(() -> editorWithBase(null).requireMayAuthor(stored, stored, "langchain"));
        }
    }
}
