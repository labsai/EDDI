/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;

/**
 * The runtime template engine as it is actually wired in a running EDDI — the
 * only place the resolvers Quarkus generates for EDDI's own template extensions
 * exist. Rendered through the template preview endpoint, which takes the
 * template text straight from the request.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DisplayName("Runtime template engine (running application)")
public class RuntimeTemplateEngineIT extends BaseIntegrationIT {

    private static final String PREVIEW = "/administration/preview/template";

    private static ValidatableResponse preview(String template) {
        return given().contentType(ContentType.JSON).body(Map.of("template", template)).post(PREVIEW).then().statusCode(200);
    }

    @Test
    @DisplayName("the config: namespace cannot read configuration")
    void configNamespaceIsUnavailable() {
        // A property every profile sets, so the precondition is not
        // environment-dependent.
        preview("[{config:['quarkus.qute.property-not-found-strategy']}][{config:property('quarkus.qute.strict-rendering')}]")
                .body("error", nullValue())
                .body("resolved", equalTo("[][]"));
    }

    @Test
    @DisplayName("inject: cannot reach beans")
    void injectNamespaceIsUnavailable() {
        preview("[{inject:templatingEngine}][{cdi:templatingEngine}]")
                .body("error", nullValue())
                .body("resolved", equalTo("[][]"));
    }

    @Test
    @DisplayName("EDDI's own template extensions still resolve")
    void eddiExtensionsResolve() {
        preview("{uuidUtils:generateUUID()}")
                .body("error", nullValue())
                .body("resolved", matchesPattern("[0-9a-f-]{36}"));
        preview("{#let s='hello'}{s.toUpperCase()} {s.replace('l','L')} {s.length()}{/let} {encoder:base64('hi')} {str:concat('a','b')}")
                .body("error", nullValue())
                .body("resolved", equalTo("HELLO heLLo 5 aGk= ab"));
    }

    @Test
    @DisplayName("methods outside the extensions are not invoked, and loops are bounded")
    void reflectionAndLoopsAreBounded() {
        preview("{#let s='ab'}[{s.repeat(3)}]{/let}")
                .body("error", nullValue())
                .body("resolved", equalTo("[]"));
        preview("{#for i in 2000000000}{/for}")
                .body("error", containsString("eddi.templating.max-iterations"));
    }
}
