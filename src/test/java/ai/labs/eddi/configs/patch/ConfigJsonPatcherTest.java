/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.patch;

import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
import ai.labs.eddi.configs.llm.rest.RestLlmStore;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.Json;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ConfigJsonPatcher")
class ConfigJsonPatcherTest {

    /** A stored LLM document the way a GET returns it: the key is a REFERENCE. */
    private static final String STORED = """
            {"tasks":[{"actions":["help"],"type":"anthropic",
             "parameters":{"apiKey":"${vault:anthropic-api-key}","modelName":"claude-a","temperature":"0.7"}}]}
            """;

    private ObjectMapper mapper;
    private ConfigJsonPatcher patcher;
    private LlmConfiguration stored;

    @BeforeEach
    void setUp() throws Exception {
        // Lenient, like the application's REST mapper: only the strict parser may
        // refuse an unknown field.
        mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        patcher = new ConfigJsonPatcher(mapper, new StrictConfigurationParser(mapper));
        stored = mapper.readValue(STORED, LlmConfiguration.class);
    }

    private LlmConfiguration apply(String patch) {
        return patcher.apply(stored, patch, LlmConfiguration.class);
    }

    private BadRequestException refused(String patch) {
        return assertThrows(BadRequestException.class, () -> apply(patch));
    }

    @Test
    @DisplayName("add and replace change only what they name — the stored reference is carried through untouched")
    void editLeavesTheStoredCredentialReferenceAlone() {
        var patched = apply("""
                [{"op":"replace","path":"/tasks/0/parameters/modelName","value":"claude-b"},
                 {"op":"add","path":"/tasks/0/parameters/maxTokens","value":"2048"}]
                """);

        var parameters = patched.tasks().get(0).getParameters();
        assertEquals("claude-b", parameters.get("modelName"));
        assertEquals("2048", parameters.get("maxTokens"));
        // The point of the whole feature: the credential was never in the patch and is
        // still exactly the reference, not resolved and not dropped.
        assertEquals("${vault:anthropic-api-key}", parameters.get("apiKey"));
        assertEquals("0.7", parameters.get("temperature"));
    }

    @Test
    @DisplayName("remove deletes a field")
    void remove() {
        var patched = apply("[{\"op\":\"remove\",\"path\":\"/tasks/0/parameters/temperature\"}]");
        assertNull(patched.tasks().get(0).getParameters().get("temperature"));
    }

    @Test
    @DisplayName("a test operation that holds lets the patch through")
    void testOperationHolds() {
        var patched = apply("""
                [{"op":"test","path":"/tasks/0/parameters/modelName","value":"claude-a"},
                 {"op":"replace","path":"/tasks/0/parameters/modelName","value":"claude-b"}]
                """);
        assertEquals("claude-b", patched.tasks().get(0).getParameters().get("modelName"));
    }

    @Test
    @DisplayName("a test operation that does not hold is a 400 and nothing is returned")
    void testOperationFails() {
        var e = refused("""
                [{"op":"test","path":"/tasks/0/parameters/modelName","value":"something-else"},
                 {"op":"replace","path":"/tasks/0/parameters/modelName","value":"claude-b"}]
                """);
        assertEquals(400, e.getResponse().getStatus());
        assertTrue(e.getResponse().getEntity().toString().contains("could not be applied"), e.getResponse().getEntity().toString());
    }

    @Test
    @DisplayName("replace or remove of a path that does not exist is a 400")
    void badPath() {
        assertEquals(400, refused("[{\"op\":\"replace\",\"path\":\"/tasks/7/parameters/modelName\",\"value\":\"x\"}]").getResponse().getStatus());
        assertEquals(400, refused("[{\"op\":\"remove\",\"path\":\"/tasks/0/parameters/nope\"}]").getResponse().getStatus());
    }

    @Test
    @DisplayName("an unknown operation, a missing path and a non-array body are 400s")
    void malformedPatch() {
        assertEquals(400, refused("[{\"op\":\"frobnicate\",\"path\":\"/tasks\"}]").getResponse().getStatus());
        assertEquals(400, refused("[{\"op\":\"add\",\"value\":1}]").getResponse().getStatus());
        assertEquals(400, refused("{\"op\":\"add\",\"path\":\"/tasks\",\"value\":1}").getResponse().getStatus());
        assertEquals(400, refused("[{\"op\":\"add\"").getResponse().getStatus());
        assertEquals(400, refused("").getResponse().getStatus());
        assertEquals(400, refused(null).getResponse().getStatus());
    }

    @Test
    @DisplayName("a patched document the model rejects is refused exactly as a PUT body would be — unknown field")
    void unknownFieldIsRefusedLikeAPut() {
        var e = refused("[{\"op\":\"add\",\"path\":\"/tasks/0/enableBuiltInToolz\",\"value\":true}]");
        assertEquals(400, e.getResponse().getStatus());
        assertTrue(e.getResponse().getEntity().toString().contains("enableBuiltInToolz"), e.getResponse().getEntity().toString());
    }

    @Test
    @DisplayName("a patched document the model rejects is refused — wrong shape")
    void wrongShapeIsRefusedLikeAPut() {
        var e = refused("[{\"op\":\"replace\",\"path\":\"/tasks\",\"value\":\"not a list\"}]");
        assertEquals(400, e.getResponse().getStatus());
    }

    @Test
    @DisplayName("replacing the root with a scalar is a 400, not a 500")
    void rootScalar() {
        assertEquals(400, refused("[{\"op\":\"replace\",\"path\":\"\",\"value\":5}]").getResponse().getStatus());
    }

    @Test
    @DisplayName("an oversized patch and one with too many operations are refused")
    void bounds() {
        var ops = new StringBuilder("[");
        for (int i = 0; i <= ConfigJsonPatcher.MAX_OPERATIONS; i++) {
            ops.append(i == 0 ? "" : ",").append("{\"op\":\"test\",\"path\":\"/tasks/0/type\",\"value\":\"anthropic\"}");
        }
        assertEquals(400, refused(ops.append("]").toString()).getResponse().getStatus());

        String big = "[{\"op\":\"add\",\"path\":\"/tasks/0/parameters/x\",\"value\":\"" + "a".repeat(ConfigJsonPatcher.MAX_PATCH_BYTES) + "\"}]";
        assertEquals(400, refused(big).getResponse().getStatus());
    }

    @Test
    @DisplayName("a duplicate key anywhere in the patch is a 400 — the approval guard and the server must read one patch")
    void duplicateKeysAreRefused() {
        // The Manager's guard reads with JSON.parse (last key wins). Whatever Parsson
        // does with these, the server refuses them rather than choose.
        for (String patch : new String[]{
                // in an operation: the guard could judge /x while the server applies
                // /tasks/0/toolApprovals
                "[{\"op\":\"replace\",\"path\":\"/tasks/0/x\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"m\"}]",
                "[{\"op\":\"test\",\"op\":\"replace\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"m\"}]",
                // inside a value
                "[{\"op\":\"add\",\"path\":\"/tasks/0/parameters/x\",\"value\":{\"a\":1,\"a\":2}}]",
                "[{\"op\":\"add\",\"path\":\"/tasks/0/parameters/x\",\"value\":[{\"a\":{\"b\":1,\"b\":2}}]}]",
                // a repeated value key
                "[{\"op\":\"add\",\"path\":\"/tasks/0/parameters/x\",\"value\":1,\"value\":2}]"}) {
            var e = refused(patch);
            assertEquals(400, e.getResponse().getStatus(), patch);
            assertTrue(e.getResponse().getEntity().toString().contains("unambiguous"), e.getResponse().getEntity().toString());
        }
    }

    @Test
    @DisplayName("documents why the check exists: Parsson itself accepts a duplicate key silently, the last one winning")
    void parssonDefaultIsSilent() {
        try (var reader = Json.createReader(new StringReader("{\"a\":1,\"a\":2}"))) {
            assertEquals(2, reader.readObject().getInt("a"));
        }
    }

    @Test
    @DisplayName("text after the patch is a 400 rather than ignored")
    void trailingTextIsRefused() {
        assertEquals(400, refused("[{\"op\":\"remove\",\"path\":\"/tasks/0/parameters/temperature\"}] [{\"op\":\"remove\",\"path\":\"/tasks\"}]")
                .getResponse().getStatus());
    }

    @Test
    @DisplayName("the size limit counts UTF-8 bytes, not characters")
    void limitIsInBytes() {
        // 'é' is two bytes: half the limit in characters is the whole limit in bytes.
        String value = "é".repeat(ConfigJsonPatcher.MAX_PATCH_BYTES / 2 + 1);
        String patch = "[{\"op\":\"add\",\"path\":\"/tasks/0/parameters/x\",\"value\":\"" + value + "\"}]";
        assertTrue(patch.length() < ConfigJsonPatcher.MAX_PATCH_BYTES);
        assertEquals(400, refused(patch).getResponse().getStatus());
    }

    @Test
    @DisplayName("the stored document is not mutated by a patch")
    void storedDocumentUntouched() {
        apply("[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"claude-b\"}]");
        assertEquals("claude-a", stored.tasks().get(0).getParameters().get("modelName"));
    }

    @Test
    @DisplayName("works for any configuration model, not just LLM — mcpcalls")
    void otherModels() throws Exception {
        var mcp = mapper.readValue("""
                {"mcpServerUrl":"https://example.invalid/mcp","name":"s","transport":"http","apiKey":"${vault:mcp-key}"}
                """, McpCallsConfiguration.class);
        var patched = patcher.apply(mcp, "[{\"op\":\"replace\",\"path\":\"/name\",\"value\":\"renamed\"}]", McpCallsConfiguration.class);
        assertNotNull(patched);
        assertEquals("renamed", patched.getName());
        assertEquals("${vault:mcp-key}", patched.getApiKey());
    }

    /** The plaintext credential {@link #STORED_PLAINTEXT} holds. */
    private static final String PLAINTEXT_CREDENTIAL = "plain-test-credential-0123456789abcdef";
    /**
     * A stored LLM document holding a PLAINTEXT key, which a non-admin's reads
     * redact.
     */
    private static final String STORED_PLAINTEXT = """
            {"tasks":[{"actions":["help"],"type":"openai","description":"d",
             "parameters":{"apiKey":"plain-test-credential-0123456789abcdef","modelName":"gpt-a"}}]}
            """;

    @Test
    @DisplayName("a caller whose reads are redacted cannot copy a plaintext credential into a readable field")
    void protectedCredentialCannotBeCopiedOut() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        var e = assertThrows(BadRequestException.class, () -> patcher.apply(plaintext, """
                [{"op":"copy","from":"/tasks/0/parameters/apiKey","path":"/tasks/0/description"}]
                """, LlmConfiguration.class, true));
        String body = String.valueOf(e.getResponse().getEntity());
        assertTrue(body.contains("/tasks/0/description"), body);
        assertFalse(body.contains(PLAINTEXT_CREDENTIAL), body);
    }

    @Test
    @DisplayName("a caller whose reads are redacted cannot move a plaintext credential, and a test cannot probe it")
    void protectedCredentialCannotBeMovedOrProbed() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        assertThrows(BadRequestException.class, () -> patcher.apply(plaintext, """
                [{"op":"move","from":"/tasks/0/parameters/apiKey","path":"/tasks/0/parameters/modelName"}]
                """, LlmConfiguration.class, true));
        assertThrows(BadRequestException.class, () -> patcher.apply(plaintext, """
                [{"op":"test","path":"/tasks/0/parameters/apiKey","value":"plain-test-credential-0123456789abcdef"}]
                """, LlmConfiguration.class, true));
    }

    @Test
    @DisplayName("an unrelated edit by a caller whose reads are redacted keeps the stored credential intact")
    void protectedCredentialSurvivesOrdinaryEdit() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        var patched = patcher.apply(plaintext, "[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/modelName\",\"value\":\"gpt-b\"}]",
                LlmConfiguration.class, true);
        assertEquals("gpt-b", patched.tasks().get(0).getParameters().get("modelName"));
        assertEquals("plain-test-credential-0123456789abcdef", patched.tasks().get(0).getParameters().get("apiKey"));
    }

    @Test
    @DisplayName("removing an earlier array element shifts a protected credential's index without refusing the patch")
    void protectedCredentialSurvivesIndexShift() throws Exception {
        var plaintext = mapper.readValue("""
                {"tasks":[{"actions":["a"],"type":"openai","description":"first","parameters":{"modelName":"gpt-a"}},
                 {"actions":["b"],"type":"openai","description":"second",
                  "parameters":{"apiKey":"plain-test-credential-0123456789abcdef","modelName":"gpt-b"}}]}
                """, LlmConfiguration.class);
        var patched = patcher.apply(plaintext, "[{\"op\":\"remove\",\"path\":\"/tasks/0\"}]", LlmConfiguration.class, true);
        assertEquals(1, patched.tasks().size());
        assertEquals("plain-test-credential-0123456789abcdef", patched.tasks().get(0).getParameters().get("apiKey"));
    }

    @Test
    @DisplayName("relocation is judged by the store's own read rules: toolCacheScopes is not redacted by an LLM read")
    void relocationIntoAPathTheReadSkipsIsRefused() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        String patch = """
                [{"op":"add","path":"/tasks/0/toolCacheScopes","value":{}},
                 {"op":"copy","from":"/tasks/0/parameters/apiKey","path":"/tasks/0/toolCacheScopes/apiKey"}]
                """;

        // The generic rules hide a value named apiKey anywhere, but RestLlmStore's read
        // leaves toolCacheScopes as it is, so the copy would come back in plain text.
        var e = assertThrows(BadRequestException.class,
                () -> patcher.apply(plaintext, patch, LlmConfiguration.class, true, RestLlmStore.NOT_CONFIGURATION));
        String body = String.valueOf(e.getResponse().getEntity());
        assertTrue(body.contains("/tasks/0/toolCacheScopes/apiKey"), body);
        assertFalse(body.contains(PLAINTEXT_CREDENTIAL), body);
    }

    @Test
    @DisplayName("an administrator may still copy a credential: nothing is hidden from them")
    void adminPatchIsUnrestricted() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        var patched = patcher.apply(plaintext, """
                [{"op":"copy","from":"/tasks/0/parameters/apiKey","path":"/tasks/0/parameters/backupKey"}]
                """, LlmConfiguration.class, false);
        assertEquals("plain-test-credential-0123456789abcdef", patched.tasks().get(0).getParameters().get("backupKey"));
    }

    @Test
    @DisplayName("an editor cannot add a baseUrl beside a hidden literal apiKey — the key would be sent to their endpoint")
    void addingABaseUrlBesideAHiddenKeyIsRefused() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        String patch = "[{\"op\":\"add\",\"path\":\"/tasks/0/parameters/baseUrl\",\"value\":\"https://attacker.example/v1\"}]";

        var e = assertThrows(BadRequestException.class, () -> patcher.apply(plaintext, patch, LlmConfiguration.class, true));
        assertEquals(400, e.getResponse().getStatus());
        String body = String.valueOf(e.getResponse().getEntity());
        assertTrue(body.contains("/tasks/0/parameters/baseUrl"), body);
        assertFalse(body.contains(PLAINTEXT_CREDENTIAL), body);
        assertFalse(body.contains("attacker.example"), body);
    }

    @Test
    @DisplayName("changing the provider type beside a hidden literal apiKey is refused, as is removing a stored baseUrl")
    void changingTheProviderOrDroppingTheEndpointIsRefused() throws Exception {
        var plaintext = mapper.readValue("""
                {"tasks":[{"actions":["help"],"type":"openai","description":"d",
                 "parameters":{"apiKey":"plain-test-credential-0123456789abcdef","modelName":"gpt-a",
                  "baseUrl":"https://llm.internal.example/v1"}}]}
                """, LlmConfiguration.class);

        var type = assertThrows(BadRequestException.class, () -> patcher.apply(plaintext,
                "[{\"op\":\"replace\",\"path\":\"/tasks/0/type\",\"value\":\"anthropic\"}]", LlmConfiguration.class, true));
        assertTrue(String.valueOf(type.getResponse().getEntity()).contains("/tasks/0/type"));
        assertThrows(BadRequestException.class, () -> patcher.apply(plaintext,
                "[{\"op\":\"remove\",\"path\":\"/tasks/0/parameters/baseUrl\"}]", LlmConfiguration.class, true));
        // A different path on the same origin is the same destination.
        var samePlace = patcher.apply(plaintext,
                "[{\"op\":\"replace\",\"path\":\"/tasks/0/parameters/baseUrl\",\"value\":\"https://llm.internal.example/v2\"}]",
                LlmConfiguration.class, true);
        assertEquals(PLAINTEXT_CREDENTIAL, samePlace.tasks().get(0).getParameters().get("apiKey"));
    }

    @Test
    @DisplayName("an editor cannot change targetServerUrl on an httpcalls config whose Authorization header is a literal")
    void changingTargetServerUrlBesideAHiddenHeaderIsRefused() throws Exception {
        var httpcalls = mapper.readValue("""
                {"targetServerUrl":"https://api.partner.example",
                 "httpCalls":[{"name":"lookup","actions":["lookup"],
                  "request":{"path":"/v1/items","method":"get",
                   "headers":{"Authorization":"Bearer plain-test-credential-0123456789abcdef"}}}]}
                """, ApiCallsConfiguration.class);
        String patch = "[{\"op\":\"replace\",\"path\":\"/targetServerUrl\",\"value\":\"https://attacker.example\"}]";

        var e = assertThrows(BadRequestException.class, () -> patcher.apply(httpcalls, patch, ApiCallsConfiguration.class, true));
        String body = String.valueOf(e.getResponse().getEntity());
        assertTrue(body.contains("/targetServerUrl"), body);
        assertFalse(body.contains(PLAINTEXT_CREDENTIAL), body);

        // The same patch from an administrator, who can read the header anyway, is a
        // plain edit.
        var admin = patcher.apply(httpcalls, patch, ApiCallsConfiguration.class, false);
        assertEquals("https://attacker.example", admin.getTargetServerUrl());
    }

    @Test
    @DisplayName("an administrator may point a literal credential at another endpoint")
    void adminMayChangeTheDestination() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        var patched = patcher.apply(plaintext,
                "[{\"op\":\"add\",\"path\":\"/tasks/0/parameters/baseUrl\",\"value\":\"https://other.example/v1\"}]",
                LlmConfiguration.class, false);
        assertEquals("https://other.example/v1", patched.tasks().get(0).getParameters().get("baseUrl"));
        assertEquals(PLAINTEXT_CREDENTIAL, patched.tasks().get(0).getParameters().get("apiKey"));
    }

    @Test
    @DisplayName("a vault reference is not a hidden literal: an editor may change the endpoint beside it")
    void destinationBesideAVaultReferenceMayChange() {
        var patched = patcher.apply(stored,
                "[{\"op\":\"add\",\"path\":\"/tasks/0/parameters/baseUrl\",\"value\":\"https://other.example/v1\"}]",
                LlmConfiguration.class, true);
        assertEquals("https://other.example/v1", patched.tasks().get(0).getParameters().get("baseUrl"));
        assertEquals("${vault:anthropic-api-key}", patched.tasks().get(0).getParameters().get("apiKey"));
    }

    @Test
    @DisplayName("a destination change in another task, or a non-destination edit, does not touch the hidden key")
    void unrelatedEditsBesideAHiddenKeyAreAllowed() throws Exception {
        var plaintext = mapper.readValue("""
                {"tasks":[{"actions":["a"],"type":"openai","description":"first",
                  "parameters":{"apiKey":"plain-test-credential-0123456789abcdef","modelName":"gpt-a"}},
                 {"actions":["b"],"type":"openai","description":"second","parameters":{"modelName":"gpt-b"}}]}
                """, LlmConfiguration.class);
        var patched = patcher.apply(plaintext, """
                [{"op":"add","path":"/tasks/1/parameters/baseUrl","value":"https://other.example/v1"},
                 {"op":"replace","path":"/tasks/0/description","value":"renamed"}]
                """, LlmConfiguration.class, true);
        assertEquals(PLAINTEXT_CREDENTIAL, patched.tasks().get(0).getParameters().get("apiKey"));
        assertEquals("https://other.example/v1", patched.tasks().get(1).getParameters().get("baseUrl"));
    }

    @Test
    @DisplayName("an insert that shifts the credential's task is paired by the credential, not by index")
    void destinationCheckFollowsAShiftedCredential() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        String newTask = "{\"actions\":[\"x\"],\"type\":\"openai\",\"description\":\"new\","
                + "\"parameters\":{\"modelName\":\"gpt-x\",\"baseUrl\":\"https://other.example/v1\"}}";

        // The new task carries its own baseUrl, and the credential's task moves to
        // index 1 unchanged: compared with the new task by index this would refuse.
        var shifted = patcher.apply(plaintext, "[{\"op\":\"add\",\"path\":\"/tasks/0\",\"value\":" + newTask + "}]",
                LlmConfiguration.class, true);
        assertEquals(PLAINTEXT_CREDENTIAL, shifted.tasks().get(1).getParameters().get("apiKey"));
        assertNull(shifted.tasks().get(1).getParameters().get("baseUrl"));

        // ...and the shifted task is still held to its own destination.
        var e = assertThrows(BadRequestException.class, () -> patcher.apply(plaintext, "[{\"op\":\"add\",\"path\":\"/tasks/0\",\"value\":"
                + newTask + "},{\"op\":\"add\",\"path\":\"/tasks/1/parameters/baseUrl\",\"value\":\"https://attacker.example/v1\"}]",
                LlmConfiguration.class, true));
        assertTrue(String.valueOf(e.getResponse().getEntity()).contains("/tasks/1/parameters/baseUrl"));
    }

    @Test
    @DisplayName("copying the task that holds a hidden key and pointing the copy elsewhere is refused")
    void copiedCredentialIsHeldToTheOriginalDestination() throws Exception {
        var plaintext = mapper.readValue(STORED_PLAINTEXT, LlmConfiguration.class);
        assertThrows(BadRequestException.class, () -> patcher.apply(plaintext, """
                [{"op":"copy","from":"/tasks/0","path":"/tasks/1"},
                 {"op":"add","path":"/tasks/1/parameters/baseUrl","value":"https://attacker.example/v1"}]
                """, LlmConfiguration.class, true));
    }

    @Test
    @DisplayName("copies that double a growing container are refused before the result is serialised")
    void amplifyingCopiesAreRefused() {
        StringBuilder patch = new StringBuilder("[");
        for (int i = 0; i < 60; i++) {
            if (i > 0) {
                patch.append(',');
            }
            patch.append("{\"op\":\"copy\",\"from\":\"/tasks\",\"path\":\"/tasks/-\"}");
        }
        patch.append(']');
        var e = refused(patch.toString());
        assertTrue(String.valueOf(e.getResponse().getEntity()).contains("double"));
    }

    @Test
    @DisplayName("weight stops counting once it passes the limit")
    void weightIsBounded() {
        var value = Json.createReader(new StringReader("{\"a\":[1,2,3,4,5,6,7,8,9,10]}")).read();
        assertTrue(ConfigJsonPatcher.weight(value, 3) <= 5);
        assertTrue(ConfigJsonPatcher.weight(value, Long.MAX_VALUE) > 10);
    }
}
