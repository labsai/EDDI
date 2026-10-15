/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.patch;

import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.rest.StrictConfigurationParser;
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
}
