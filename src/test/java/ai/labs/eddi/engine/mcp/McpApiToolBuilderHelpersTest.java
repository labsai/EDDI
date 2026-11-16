/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.servers.ServerVariable;
import io.swagger.v3.oas.models.servers.ServerVariables;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The per-operation decisions of the OpenAPI tool builder: which inputs a
 * generated tool cannot send, which operations answer in headers, and how a
 * templated server URL becomes a literal one.
 */
class McpApiToolBuilderHelpersTest {

    private static ApiResponses responses(String status, ApiResponse response) {
        var responses = new ApiResponses();
        responses.addApiResponse(status, response);
        return responses;
    }

    private static ApiResponse withContent() {
        return new ApiResponse().content(new Content().addMediaType("application/json", new MediaType()));
    }

    @Test
    void createdAcceptedAndRedirectsAnswerInHeaders() {
        assertTrue(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("201", withContent()))));
        assertTrue(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("202", withContent()))));
        assertTrue(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("302", new ApiResponse()))));
    }

    @Test
    void aSuccessWithoutContentAnswersInHeadersButOneWithContentDoesNot() {
        assertTrue(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("204", new ApiResponse()))));
        assertTrue(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("200", null))));
        assertTrue(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("200", new ApiResponse().content(new Content())))));
        assertFalse(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("200", withContent()))));
    }

    @Test
    void undocumentedOrDefaultOnlyResponsesGetNoHeaders() {
        assertFalse(McpApiToolBuilder.returnsDataInHeaders(new Operation()));
        assertFalse(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(new ApiResponses())));
        assertFalse(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("default", new ApiResponse()))));
        assertFalse(McpApiToolBuilder.returnsDataInHeaders(new Operation().responses(responses("404", new ApiResponse()))));
    }

    @Test
    void aRequiredHeaderOrCookieCannotBeSentUnlessItIsTheAuthHeader() {
        var header = new Operation().addParametersItem(new HeaderParameter().name("X-Api-Key").required(true));
        assertTrue(McpApiToolBuilder.findUnmappableInput(header, new PathItem(), new OpenAPI(), null, "X-Api-Key")
                .contains("header parameter 'X-Api-Key'"));
        assertNull(McpApiToolBuilder.findUnmappableInput(header, new PathItem(), new OpenAPI(), "${vault:key}", "x-api-key"),
                "the configured auth header is sent on every call");
        assertTrue(McpApiToolBuilder.findUnmappableInput(header, new PathItem(), new OpenAPI(), "${vault:key}", "Authorization")
                .contains("X-Api-Key"));
        assertTrue(McpApiToolBuilder.findUnmappableInput(header, new PathItem(), new OpenAPI(), " ", "X-Api-Key").contains("X-Api-Key"),
                "a blank apiAuth configures nothing");

        var cookie = new Operation().addParametersItem(new Parameter().in("cookie").name("session").required(true));
        assertTrue(McpApiToolBuilder.findUnmappableInput(cookie, null, new OpenAPI(), "${vault:key}", "session").contains("cookie parameter"));

        var optional = new Operation().addParametersItem(new HeaderParameter().name("X-Trace").required(false));
        assertNull(McpApiToolBuilder.findUnmappableInput(optional, null, new OpenAPI(), null, null));
    }

    @Test
    void aBodyOfAnUnsupportedTypeCannotBeSentUnlessItIsOptional() {
        var xml = new RequestBody().content(new Content().addMediaType("application/xml", new MediaType()));
        assertTrue(McpApiToolBuilder.findUnmappableInput(new Operation().requestBody(xml), null, new OpenAPI(), null, null)
                .startsWith("requires a request body of type application/xml"));
        assertNull(McpApiToolBuilder.findUnmappableInput(new Operation().requestBody(xml.required(false)), null, new OpenAPI(), null, null));
        assertNull(McpApiToolBuilder.findUnmappableInput(new Operation().requestBody(new RequestBody()), null, new OpenAPI(), null, null),
                "a body without content generates no body");
    }

    @Test
    void aReferencedRequestBodyIsResolvedBeforeItIsJudged() {
        var api = new OpenAPI().components(new Components().addRequestBodies("Upload",
                new RequestBody().content(new Content().addMediaType("multipart/form-data", new MediaType()))));
        var operation = new Operation().requestBody(new RequestBody().$ref("#/components/requestBodies/Upload"));
        assertTrue(McpApiToolBuilder.findUnmappableInput(operation, null, api, null, null).contains("multipart/form-data"));

        var missing = new Operation().requestBody(new RequestBody().$ref("#/components/requestBodies/Missing"));
        assertNull(McpApiToolBuilder.findUnmappableInput(missing, null, api, null, null), "an unresolved reference stays body-less");
    }

    @Test
    void referencedPathLevelParametersAreJudgedToo() {
        var api = new OpenAPI().components(new Components().addParameters("Tenant", new HeaderParameter().name("X-Tenant").required(true)));
        var pathItem = new PathItem().addParametersItem(new Parameter().$ref("#/components/parameters/Tenant"));
        assertTrue(McpApiToolBuilder.findUnmappableInput(new Operation(), pathItem, api, null, null).contains("X-Tenant"));

        var dangling = new PathItem().addParametersItem(new Parameter().$ref("#/components/parameters/Nope"));
        assertNull(McpApiToolBuilder.findUnmappableInput(new Operation(), dangling, api, null, null));
        assertNull(McpApiToolBuilder.findUnmappableInput(new Operation(), dangling, new OpenAPI(), null, null));
    }

    @Test
    void serverVariablesAreReplacedByTheirDefaults() {
        var variables = new ServerVariables();
        variables.addServerVariable("region", new ServerVariable()._default("eu"));
        variables.addServerVariable("version", new ServerVariable()._default("v2"));
        assertEquals("https://eu.api.example.com/v2",
                McpApiToolBuilder.literalServerUrl(new Server().url("https://{region}.api.example.com/{version}").variables(variables)));
        assertEquals("https://api.example.com", McpApiToolBuilder.literalServerUrl(new Server().url("https://api.example.com")));
        assertNull(McpApiToolBuilder.literalServerUrl(new Server()));
    }

    @Test
    void aTemplateLeftInTheServerUrlIsRefused() {
        var variables = new ServerVariables();
        variables.addServerVariable("region", new ServerVariable());
        variables.addServerVariable("tenant", new ServerVariable()._default("{nested}"));
        variables.addServerVariable("unused", null);
        assertThrows(IllegalArgumentException.class,
                () -> McpApiToolBuilder.literalServerUrl(new Server().url("https://{region}.example.com").variables(variables)));
        assertThrows(IllegalArgumentException.class,
                () -> McpApiToolBuilder.literalServerUrl(new Server().url("https://{tenant}.example.com").variables(variables)));
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.literalServerUrl(new Server().url("https://x.example.com/}")));
    }

    @Test
    void theAuthHeaderDefaultsAndIsValidated() {
        assertEquals("Authorization", McpApiToolBuilder.resolveAuthHeader(null));
        assertEquals("Authorization", McpApiToolBuilder.resolveAuthHeader("  "));
        assertEquals("X-Api-Key", McpApiToolBuilder.resolveAuthHeader(" X-Api-Key "));
        assertThrows(IllegalArgumentException.class, () -> McpApiToolBuilder.resolveAuthHeader("X-Key\r\nX-Evil: 1"));
    }
}
