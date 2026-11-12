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
    @DisplayName("indirect provider names")
    class Indirect {

        @Test
        @DisplayName("a provider given as ${vars:...} or a template is refused — it could resolve to a restricted one")
        void variableOrTemplateRefused() {
            var policy = asEditor();
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task("${vars:provider}", "\"modelId\":\"m\""), null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task("{context.p}", ""), null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task("{properties.x}", ""), null, "langchain"));
            String cascade = "{\"tasks\":[{\"type\":\"openai\",\"parameters\":{},\"modelCascade\":{\"steps\":[{\"type\":\"${vars:p}\","
                    + "\"parameters\":{}}],\"judgeModel\":{\"type\":\"${vars:j}\",\"parameters\":{}}}}]}";
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(cascade, null, "langchain"));
            assertThrows(ForbiddenException.class,
                    () -> policy.requireMayAuthor("{\"contextWindow\":{\"llmProvider\":\"${vars:p}\",\"llmModel\":\"x\"}}", null, "group"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor("{\"embeddingProvider\":\"${vars:e}\"}", null, "rag"));
        }

        @Test
        @DisplayName("an unchanged indirect provider an administrator stored is carried over")
        void carryOver() {
            String stored = task("${vars:provider}", "\"systemMessage\":\"a\"");
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(task("${vars:provider}", "\"systemMessage\":\"b\""), stored, "langchain"));
        }

        @Test
        @DisplayName("case and whitespace do not hide a restricted provider")
        void normalisation() {
            var policy = asEditor();
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task(" Bedrock ", "\"modelId\":\"m\""), null, "langchain"));
            assertThrows(ForbiddenException.class, () -> policy.requireMayAuthor(task("JLAMA", ""), null, "langchain"));
        }

        @Test
        @DisplayName("a log flag given as a variable counts as on")
        void variableLogFlag() {
            assertThrows(ForbiddenException.class,
                    () -> asEditor().requireMayAuthor(task("openai", "\"logRequests\":\"${vars:debug}\""), null, "langchain"));
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

    @Nested
    @DisplayName("review round: task identity, cascade inheritance, restorability")
    class ReviewRound {

        private String twoTasks(String firstLog, String secondLog) {
            return "{\"tasks\":[{\"id\":\"a\",\"type\":\"openai\",\"parameters\":{\"logRequests\":\"" + firstLog + "\"}},"
                    + "{\"id\":\"b\",\"type\":\"openai\",\"parameters\":{\"logRequests\":\"" + secondLog + "\"}}]}";
        }

        @Test
        @DisplayName("a logging approval cannot be moved from one task to another")
        void loggingBoundToItsTask() {
            String stored = twoTasks("true", "false");
            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(twoTasks("false", "true"), stored, "langchain"));
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(twoTasks("true", "false"), stored, "langchain"));
        }

        private String bedrockWithStep(String stepParameters) {
            return "{\"tasks\":[{\"id\":\"t\",\"type\":\"bedrock\",\"parameters\":{\"modelId\":\"m1\"},"
                    + "\"modelCascade\":{\"steps\":[{\"parameters\":{" + stepParameters + "}}]}}]}";
        }

        @Test
        @DisplayName("an untyped cascade step under a Bedrock task cannot re-point its model")
        void untypedStepInheritsRestrictedProvider() {
            String stored = bedrockWithStep("");
            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(bedrockWithStep("\"modelId\":\"m2\""), stored, "langchain"));
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(stored, stored, "langchain"));
            // An id-less step is identified by its position and content, so even a
            // non-pointing edit to it needs an administrator (the stricter rule).
            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(bedrockWithStep("\"temperature\":\"0.2\""), stored, "langchain"));
        }

        private String openAiWithBedrockStep(String taskModel) {
            return "{\"tasks\":[{\"id\":\"t\",\"type\":\"openai\",\"parameters\":{\"modelName\":\"" + taskModel + "\"},"
                    + "\"modelCascade\":{\"steps\":[{\"type\":\"bedrock\",\"parameters\":{\"region\":\"eu-west-1\"}}]}}]}";
        }

        @Test
        @DisplayName("a Bedrock step cannot be re-pointed through the model name it inherits from its task")
        void restrictedStepInheritsTaskParameters() {
            String stored = openAiWithBedrockStep("m1");
            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(openAiWithBedrockStep("m2"), stored, "langchain"));
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(openAiWithBedrockStep("m1"), stored, "langchain"));
        }

        @Test
        @DisplayName("writing an administrator's Bedrock config back over an editor's replacement is refused — so a merge checks it first")
        void restoreOverRemovalRefused() {
            String adminBedrock = task("bedrock", "\"modelId\":\"m1\"");
            String editorReplacement = task("openai", "\"modelName\":\"gpt\"");
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(editorReplacement, adminBedrock, "langchain"));
            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(adminBedrock, editorReplacement, "existing (restorable)"));
        }
    }

    @Nested
    @DisplayName("review round 2: facts bound to their element, duplicate ids")
    class ElementIdentity {

        private String bedrockTask(String id, String modelId, String stepParameters) {
            return "{\"id\":\"" + id + "\",\"type\":\"bedrock\",\"parameters\":{\"modelId\":\"" + modelId + "\"},"
                    + "\"modelCascade\":{\"steps\":[{\"parameters\":{" + stepParameters + "}}]}}";
        }

        private String tasks(String... tasks) {
            return "{\"tasks\":[" + String.join(",", tasks) + "]}";
        }

        @Test
        @DisplayName("a restricted setting approved on one task does not carry over to another task's step")
        void factsAreBoundToTheirTaskAndStep() {
            // Task b runs Bedrock m2 with the administrator's approval. Task a runs m1.
            String stored = tasks(bedrockTask("a", "m1", ""), bedrockTask("b", "m2", ""));
            // An untyped step under task a re-pointed to m2: m2 is approved — for task b.
            String candidate = tasks(bedrockTask("a", "m1", "\"modelId\":\"m2\""), bedrockTask("b", "m2", ""));

            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(candidate, stored, "langchain"));
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(stored, stored, "langchain"));
        }

        @Test
        @DisplayName("a restricted task's approval does not move to a task with another id")
        void taskIdentityForProviders() {
            String stored = tasks(task0("a", "bedrock", "m1"), task0("b", "openai", "gpt"));
            String candidate = tasks(task0("a", "openai", "gpt"), task0("b", "bedrock", "m1"));

            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(candidate, stored, "langchain"));
        }

        private String task0(String id, String type, String model) {
            return "{\"id\":\"" + id + "\",\"type\":\"" + type + "\",\"parameters\":{\"modelId\":\"" + model + "\"}}";
        }

        @Test
        @DisplayName("duplicate task ids carry nothing over, in the stored document or the new one")
        void duplicateIdsCarryNothingOver() {
            String duplicate = tasks(task0("a", "bedrock", "m1"), task0("a", "openai", "gpt"));
            String unique = tasks(task0("a", "bedrock", "m1"));

            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(duplicate, unique, "langchain"));
            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(unique, duplicate, "langchain"));
            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(duplicate, duplicate, "langchain"));
            // Without a privileged setting, duplicate ids are not the policy's concern.
            String plain = tasks(task0("a", "openai", "gpt"), task0("a", "openai", "gpt"));
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(plain, null, "langchain"));
        }
    }

    @Nested
    @DisplayName("id-less elements")
    class IdLessElements {

        private String idless(String model, String log) {
            return "{\"type\":\"openai\",\"parameters\":{\"modelName\":\"" + model + "\",\"logRequests\":\"" + log + "\"}}";
        }

        @Test
        @DisplayName("removing an approved id-less task and shifting another into its slot does not carry the approval over")
        void removeAndShiftRefused() {
            // Position 0 is approved for logging; position 1 is another task.
            String stored = "{\"tasks\":[" + idless("approved", "true") + "," + idless("other", "false") + "]}";
            // Position 0 removed; the other task shifts into slot 0 with logging on.
            String candidate = "{\"tasks\":[" + idless("other", "true") + "]}";

            assertThrows(ForbiddenException.class, () -> asEditor().requireMayAuthor(candidate, stored, "langchain"));
        }

        @Test
        @DisplayName("an unchanged id-less task keeps its approval")
        void unchangedIdLessTaskPasses() {
            String stored = "{\"tasks\":[" + idless("approved", "true") + "," + idless("other", "false") + "]}";
            assertDoesNotThrow(() -> asEditor().requireMayAuthor(stored, stored, "langchain"));
        }
    }
}
