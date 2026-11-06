/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("McpApiToolBuilder — generated argumentSchema")
class McpApiToolBuilderArgumentSchemaTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SPEC = """
            {"openapi":"3.0.3","info":{"title":"t","version":"1"},"servers":[{"url":"https://t.example.com"}],
             "paths":{
              "/teams/{teamId}/invites":{"post":{"operationId":"invite","tags":["teams"],
                "parameters":[
                  {"name":"teamId","in":"path","required":true,"schema":{"type":"integer"}},
                  {"name":"notify","in":"query","schema":{"type":"boolean"}},
                  {"name":"mode","in":"query","schema":{"type":"string","enum":["fast","safe"]}},
                  {"name":"tags","in":"query","schema":{"type":"array","items":{"type":"string"}}},
                  {"$ref":"#/components/parameters/Locale"}],
                "requestBody":{"required":true,"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Invite"}}}},
                "responses":{"201":{"description":"ok"}}}},
              "/notes":{"put":{"operationId":"setNote","tags":["teams"],
                "requestBody":{"content":{"text/plain":{"schema":{"type":"string"}}}},
                "responses":{"200":{"description":"ok"}}}},
              "/tree":{"post":{"operationId":"saveTree","tags":["teams"],
                "requestBody":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Node"}}}},
                "responses":{"200":{"description":"ok"}}}}
             },
             "components":{
              "parameters":{"Locale":{"name":"locale","in":"query","schema":{"type":"string"}}},
              "schemas":{
               "Invite":{"type":"object","required":["emails","role"],"properties":{
                 "id":{"type":"string","readOnly":true},
                 "emails":{"type":"array","minItems":1,"items":{"type":"string"}},
                 "role":{"$ref":"#/components/schemas/Role"},
                 "profile":{"allOf":[{"$ref":"#/components/schemas/Base"},{"type":"object","properties":{"age":{"type":"integer","minimum":0}}}]},
                 "extra":{"oneOf":[{"type":"string"},{"type":"integer"}]},
                 "meta":{"type":"object","additionalProperties":{"type":"string"}},
                 "nick":{"type":"string","nullable":true}}},
               "Role":{"type":"string","enum":["manager","curator"],"description":"The role"},
               "Base":{"type":"object","required":["name"],"properties":{"name":{"type":"string"}}},
               "Node":{"type":"object","properties":{"label":{"type":"string"},"children":{"type":"array","items":{"$ref":"#/components/schemas/Node"}}}}
              }}}
            """;

    private static ApiCall call(String name) {
        var result = McpApiToolBuilder.parseAndBuild(SPEC, null, null, null);
        return result.configsByGroup().values().stream().flatMap(c -> c.getHttpCalls().stream()).filter(c -> name.equals(c.getName()))
                .findFirst().orElseThrow();
    }

    private static JsonNode schemaOf(String name) {
        ApiCall call = call(name);
        assertNotNull(call.getArgumentSchema(), "the generated call carries a schema");
        assertEquals(ApiCall.UNKNOWN_FIELDS_REJECT, call.getUnknownFields());
        return MAPPER.valueToTree(call.getArgumentSchema());
    }

    @Test
    @DisplayName("path and query parameters are typed scalars; an array query parameter stays a string")
    void parameterTypes() {
        JsonNode properties = schemaOf("invite").get("properties");
        assertEquals("integer", properties.at("/teamId/type").asText());
        assertEquals("boolean", properties.at("/notify/type").asText());
        assertEquals("[\"fast\",\"safe\"]", properties.at("/mode/enum").toString());
        assertEquals("string", properties.at("/tags/type").asText());
        assertEquals("string", properties.at("/locale/type").asText(), "a $ref'd parameter is resolved");
    }

    @Test
    @DisplayName("path parameters and the body are required; a query parameter only when the spec says so")
    void topLevelRequired() {
        JsonNode schema = schemaOf("invite");
        List<String> required = MAPPER.convertValue(schema.get("required"), List.class);
        assertEquals(List.of("teamId", "requestBody"), required);
    }

    @Test
    @DisplayName("an optional query parameter is recorded, so a value the model leaves out is not sent as ?name=")
    void optionalQueryParamsRecorded() {
        // By the spec's NAME (locale comes through a $ref), not the tool variable.
        assertEquals(List.of("notify", "mode", "tags", "locale"), call("invite").getRequest().getOptionalQueryParams());
    }

    private static final String REQUIRED_QUERY_SPEC = """
            {"openapi":"3.0.3","info":{"title":"t","version":"1"},"servers":[{"url":"https://t.example.com"}],
             "paths":{"/docs/{id}":{"patch":{"operationId":"patchDoc","tags":["d"],
               "parameters":[
                 {"name":"id","in":"path","required":true,"schema":{"type":"string"}},
                 {"name":"version","in":"query","required":true,"schema":{"type":"integer"}},
                 {"name":"dryRun","in":"query","schema":{"type":"boolean"}}],
               "responses":{"200":{"description":"ok"}}}}}}
            """;

    @Test
    @DisplayName("a query parameter the spec marks required stays required and is always sent")
    void requiredQueryParamStaysRequired() {
        ApiCall call = McpApiToolBuilder.parseAndBuild(REQUIRED_QUERY_SPEC, null, null, null).configsByGroup().values().stream()
                .flatMap(c -> c.getHttpCalls().stream()).findFirst().orElseThrow();
        JsonNode schema = MAPPER.valueToTree(call.getArgumentSchema());
        assertEquals("[\"id\",\"version\"]", schema.get("required").toString());
        assertEquals(List.of("dryRun"), call.getRequest().getOptionalQueryParams());
    }

    private static final String REPEATED_IDS_SPEC = """
            {"openapi":"3.0.3","info":{"title":"t","version":"1"},"servers":[{"url":"https://t.example.com"}],
             "paths":{
              "/groups/{groupId}/lessons":{"get":{"operationId":"list","tags":["g"],
                "parameters":[{"name":"groupId","in":"path","required":true,"schema":{"type":"string"}}],
                "responses":{"200":{"description":"ok"}}}},
              "/groups/{groupId}/outcomes":{"get":{"operationId":"list","tags":["g"],
                "parameters":[{"name":"groupId","in":"path","required":true,"schema":{"type":"string"}}],
                "responses":{"200":{"description":"ok"}}}},
              "/things":{"get":{"operationId":"list","tags":["t"],"responses":{"200":{"description":"ok"}}}}}}
            """;

    @Test
    @DisplayName("a repeated operationId gives every operation its own tool name, so none is dropped at assembly")
    void repeatedOperationIdsStayDistinct() {
        List<ApiCall> calls = McpApiToolBuilder.parseAndBuild(REPEATED_IDS_SPEC, null, null, null).configsByGroup().values().stream()
                .flatMap(c -> c.getHttpCalls().stream()).toList();
        List<String> names = calls.stream().map(ApiCall::getName).toList();
        assertEquals(3, names.size(), names.toString());
        assertEquals(3, names.stream().distinct().count(), "names must be unique: " + names);
        // The first keeps its operationId; a repeat takes the method-and-path slug.
        assertEquals("list", names.get(0));
        assertTrue(names.contains("get_groups_groupid_outcomes"), names.toString());
        for (ApiCall call : calls) {
            assertEquals(call.getName() + "_response", call.getResponseObjectName());
        }
    }

    @Test
    @DisplayName("uniqueToolName falls back to a numeric suffix when the slug is taken too")
    void uniqueToolNameSuffix() {
        var taken = new HashSet<>(Set.of("list", "get_things"));
        assertEquals("get_things_2", McpApiToolBuilder.uniqueToolName("list", "get", "/things", taken));
        assertEquals("fresh", McpApiToolBuilder.uniqueToolName("fresh", "get", "/x", taken));
        assertTrue(taken.containsAll(Set.of("get_things_2", "fresh")));
    }

    @Test
    @DisplayName("the body schema is resolved: $refs, nested arrays, enums, allOf, nullable, readOnly left out")
    void bodySchema() {
        JsonNode body = schemaOf("invite").at("/properties/requestBody");
        assertEquals("object", body.get("type").asText());
        assertEquals("[\"emails\",\"role\"]", body.get("required").toString());
        assertFalse(body.get("properties").has("id"), "readOnly fields are not sent");
        assertEquals("string", body.at("/properties/emails/items/type").asText());
        assertEquals(1, body.at("/properties/emails/minItems").asInt());
        assertEquals("[\"manager\",\"curator\"]", body.at("/properties/role/enum").toString());
        assertEquals("The role", body.at("/properties/role/description").asText());
        JsonNode profile = body.at("/properties/profile");
        assertEquals("object", profile.get("type").asText());
        assertTrue(profile.get("properties").has("name") && profile.get("properties").has("age"), profile.toString());
        assertEquals("[\"name\"]", profile.get("required").toString());
        assertEquals(0, profile.at("/properties/age/minimum").asInt());
        assertFalse(body.at("/properties/extra").has("type"), "oneOf is untyped: anything is accepted");
        assertTrue(body.at("/properties/meta/additionalProperties").asBoolean(), "a map admits any key");
        assertTrue(body.at("/properties/nick/nullable").asBoolean());
        assertFalse(body.toString().contains("$ref"), body.toString());
    }

    @Test
    @DisplayName("a recursive schema terminates: the recursion point accepts anything")
    void recursiveSchema() {
        JsonNode body = schemaOf("saveTree").at("/properties/requestBody");
        assertEquals("array", body.at("/properties/children/type").asText());
        assertFalse(body.at("/properties/children/items").has("type"), body.toString());
    }

    @Test
    @DisplayName("a text body is a string argument")
    void textBody() {
        assertEquals("string", schemaOf("setNote").at("/properties/requestBody/type").asText());
    }
}
