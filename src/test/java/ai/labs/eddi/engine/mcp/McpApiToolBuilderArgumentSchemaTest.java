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

import java.util.List;

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
    @DisplayName("every tool parameter is required, as the untyped tools made them")
    void topLevelRequired() {
        JsonNode schema = schemaOf("invite");
        List<String> required = MAPPER.convertValue(schema.get("required"), List.class);
        assertEquals(List.of("teamId", "notify", "mode", "tags", "locale", "requestBody"), required);
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
