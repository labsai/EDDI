/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import ai.labs.eddi.engine.runtime.BaseRuntime;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for Infrastructure endpoints: health probes, metrics,
 * OpenAPI spec, coordinator admin, and tenant quota.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class InfrastructureIT {

    /**
     * The release version now reaches the running application only by resolution:
     * {@code systemRuntime.projectVersion=${quarkus.application.version}} in
     * application.properties, expanded by SmallRye Config at startup and injected
     * here through {@code @ConfigProperty}. Nothing about that is visible to a file
     * sweep, and a failure is a startup error inside a container.
     */
    @Inject
    BaseRuntime baseRuntime;

    /**
     * {@code <version>x.y.z</version>} — the first one in the pom is the project's.
     */
    private static String pomVersion() throws IOException {
        Matcher matcher = Pattern.compile("<version>([^<]+)</version>")
                .matcher(Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8));
        assertTrue(matcher.find(), "no <version> element in pom.xml");
        return matcher.group(1);
    }

    // ==================== Health Probes ====================

    @Test
    @Order(1)
    @DisplayName("Liveness probe should return UP")
    void liveness() {
        given().get("/q/health/live")
                .then().assertThat()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    @Order(2)
    @DisplayName("Readiness probe should return UP")
    void readiness() {
        given().get("/q/health/ready")
                .then().assertThat()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    @Test
    @Order(3)
    @DisplayName("Overall health should return UP")
    void overallHealth() {
        given().get("/q/health")
                .then().assertThat()
                .statusCode(200)
                .body("status", equalTo("UP"));
    }

    // ==================== Metrics ====================

    @Test
    @Order(4)
    @DisplayName("Prometheus metrics endpoint should return metrics")
    void prometheusMetrics() {
        given().get("/q/metrics")
                .then().assertThat()
                .statusCode(200);
    }

    // ==================== Release version ====================

    /**
     * U3. {@code systemRuntime.projectVersion} stopped being the literal
     * {@code 6.3.0} and became {@code ${quarkus.application.version}}, so the value
     * every consumer sees is now produced by SmallRye Config expression expansion
     * at startup rather than by a string in a file. Maven does not filter
     * {@code application.properties} — the expression is still verbatim in
     * {@code target/classes} — so nothing before this point proves the expansion
     * happens: {@code mvn package} only proves the BUILD-time properties resolve.
     * <p>
     * The failure mode is not a wrong version but a startup
     * {@code NoSuchElementException} or a User-Agent reading
     * {@code EDDI.LABS.AI/${quarkus.application.version}} —
     * {@code HttpClientWrapper} interpolates the same property into every outbound
     * request.
     */
    @Test
    @Order(13)
    @DisplayName("the injected release version resolves to the Maven project version")
    void injectedProjectVersionResolves() throws IOException {
        String injected = baseRuntime.getVersion();

        assertTrue(injected != null && !injected.isBlank(), "systemRuntime.projectVersion resolved to nothing");
        assertFalse(injected.contains("${"),
                "systemRuntime.projectVersion was injected unexpanded as '" + injected + "'. It is served in the"
                        + " startup banner and interpolated into HttpClientWrapper's User-Agent on every outbound"
                        + " request, so an unexpanded expression leaves the deployment.");
        assertEquals(pomVersion(), injected,
                "the running application reports a different version from pom.xml, which AGENTS.md §1 makes the"
                        + " single source of truth");
    }

    // ==================== OpenAPI ====================

    /**
     * U2. {@code OpenApiConfig}'s {@code @Info(version = …)} is now the sentinel
     * {@code resolved-from-configuration}: an annotation element must be a
     * compile-time constant, so it cannot derive from pom.xml, and
     * {@code Info.version()} declares no default so it cannot be dropped. The real
     * value comes from {@code quarkus.smallrye-openapi.info-version} winning the
     * SmallRye merge.
     * <p>
     * That merge is now load-bearing in a way it was not before. If it ever stops
     * winning — an extension upgrade, a renamed property, a profile override — the
     * PUBLIC document at {@code /openapi} advertises
     * {@code version: resolved-from-configuration} to every SDK generator and API
     * consumer. The old failure mode was a stale but plausible number; the new one
     * is obvious nonsense, shipped. Nothing read this field: the assertion above
     * checks only that the body contains the word "openapi".
     */
    @Test
    @Order(14)
    @DisplayName("the served OpenAPI document carries the real release version, not the sentinel")
    void openApiInfoVersionIsResolved() throws Exception {
        String spec = given()
                .get("/openapi")
                .then().assertThat().statusCode(200)
                .extract().asString();

        // Format-agnostic for the same reason as the $ref sweep below: /openapi
        // serves YAML by default and JSON on request, and which arrives is
        // smallrye's business, not this test's.
        JsonNode info = (spec.stripLeading().startsWith("{") ? new ObjectMapper() : new YAMLMapper())
                .readTree(spec).path("info");
        String served = info.path("version").asText();

        assertNotEquals("resolved-from-configuration", served,
                "the /openapi document is serving OpenApiConfig's sentinel, so quarkus.smallrye-openapi.info-version"
                        + " is no longer winning the SmallRye merge. Every SDK generator pointed at this spec now"
                        + " reads that string as the API version.");
        assertEquals(pomVersion(), served,
                "the served info.version must be the Maven project version — it reaches API consumers and SDK"
                        + " generators, and it is derived through ${quarkus.application.version}: " + info);
    }

    @Test
    @Order(5)
    @DisplayName("OpenAPI spec should return valid document")
    void openApiSpec() {
        // Served at /openapi, not the Quarkus default /q/openapi.
        // See quarkus.smallrye-openapi.path in application.properties.
        // Accepting 404 here would let the endpoint break unnoticed.
        given()
                .get("/openapi")
                .then().assertThat()
                .statusCode(200)
                .body(containsString("openapi"))
                .body(containsString("/agentstore/agents"));
    }

    /**
     * Every {@code $ref} in the generated document must point at a schema that
     * actually exists.
     * <p>
     * This runs against the REAL generated spec because that is the only place the
     * defect it guards can appear. Declaring a REST model field as an interface
     * ({@code IConversationMemory.IConversationProperties}) made smallrye emit a
     * {@code $ref} to a schema it then never generated — invisible to every
     * assertion that only checks the endpoint returns a document, and invisible to
     * unit tests, which use hand-written specs. It broke real consumers:
     * swagger-parser dereferences, so EDDI's own {@code setup-api} wizard threw a
     * stack trace on every run while reading EDDI's own spec.
     * <p>
     * Deliberately a whole-document sweep rather than an assertion about the one
     * field that regressed — any model that acquires the same shape fails here.
     */
    @Test
    @Order(12)
    @DisplayName("OpenAPI spec should contain no dangling schema $refs")
    void openApiSpecHasNoDanglingSchemaRefs() throws Exception {
        String spec = given()
                .get("/openapi")
                .then().assertThat().statusCode(200)
                .extract().asString();

        // Format-agnostic on purpose, and no Accept header: /openapi serves YAML by
        // default and JSON on request, and which one arrives is smallrye's business,
        // not this test's — the property under test is identical either way.
        // Sniffing the body beats asserting a content negotiation it has no stake in.
        JsonNode root = (spec.stripLeading().startsWith("{") ? new ObjectMapper() : new YAMLMapper()).readTree(spec);
        JsonNode schemas = root.path("components").path("schemas");
        assertTrue(schemas.isObject() && !schemas.isEmpty(), "spec exposes no component schemas — the sweep would be vacuous");

        Set<String> dangling = new TreeSet<>();
        collectDanglingSchemaRefs(root, schemas, dangling);
        assertTrue(dangling.isEmpty(), "OpenAPI document references schemas that were never generated: " + dangling);

        // Named explicitly as well: the sweep above would still pass if the field
        // stopped being emitted at all, which is a different kind of broken.
        JsonNode properties = schemas.path("SimpleConversationMemorySnapshot").path("properties").path("conversationProperties");
        assertEquals("object", properties.path("type").asText(),
                "conversationProperties must serialise as a map, not a named type: " + properties);
        assertEquals("#/components/schemas/Property", properties.path("additionalProperties").path("$ref").asText(),
                "conversationProperties must be a map of Property: " + properties);
    }

    /**
     * Collects every {@code #/components/schemas/X} reference where X is absent.
     */
    private static void collectDanglingSchemaRefs(JsonNode node, JsonNode schemas, Set<String> dangling) {
        if (node.isObject()) {
            var refNode = node.get("$ref");
            if (refNode != null && refNode.isTextual()) {
                String ref = refNode.asText();
                String prefix = "#/components/schemas/";
                if (ref.startsWith(prefix)) {
                    String name = ref.substring(prefix.length());
                    if (!schemas.has(name)) {
                        dangling.add(name);
                    }
                }
            }
            node.forEach(child -> collectDanglingSchemaRefs(child, schemas, dangling));
        } else if (node.isArray()) {
            node.forEach(child -> collectDanglingSchemaRefs(child, schemas, dangling));
        }
    }

    @Test
    @Order(6)
    @DisplayName("Swagger UI should be accessible")
    void swaggerUi() {
        given().get("/q/swagger-ui")
                .then().assertThat()
                .statusCode(anyOf(equalTo(200), equalTo(301), equalTo(302)));
    }

    @Test
    @Order(10)
    @DisplayName("Swagger UI should receive exactly one relaxed CSP header")
    void swaggerUiCspHeader() {
        // Regression guard: if both the default and swagger CSP filters match this
        // path,
        // the browser receives two Content-Security-Policy headers and enforces the
        // most-restrictive intersection — breaking Swagger UI's inline scripts.
        // Follow redirects — /q/swagger-ui may 301→/q/swagger-ui/ in some profiles.
        var response = given().redirects().follow(true).get("/q/swagger-ui/");
        var cspHeaders = response.headers().getValues("Content-Security-Policy");
        // In test profiles, swagger-ui filters may not be active — skip gracefully
        Assumptions.assumeFalse(cspHeaders.isEmpty(),
                "Swagger UI CSP header not present in test profile — skipping assertion");
        Assertions.assertEquals(1, cspHeaders.size(),
                "Expected exactly 1 CSP header on /q/swagger-ui/ but got " + cspHeaders.size()
                        + ": " + cspHeaders);
        var csp = cspHeaders.getFirst();
        Assertions.assertTrue(csp.contains("'unsafe-inline'"),
                "Swagger UI CSP must allow 'unsafe-inline' for inline scripts: " + csp);
        Assertions.assertTrue(csp.contains("'unsafe-eval'"),
                "Swagger UI CSP must allow 'unsafe-eval' for JSON schema rendering: " + csp);
        // The GitHub exception belongs to the application policy alone. Swagger UI
        // never calls GitHub, and the two headers sit in one properties block edited
        // by hand — so a copy-paste that widens this one has to fail here.
        var swaggerConnectSrc = extractDirective(csp, "connect-src");
        Assertions.assertFalse(swaggerConnectSrc.contains("api.github.com"),
                "Swagger UI connect-src must NOT carry the GitHub exception: " + swaggerConnectSrc);
    }

    @Test
    @Order(11)
    @DisplayName("Non-Swagger paths should receive exactly one strict CSP header")
    void apiPathCspHeader() {
        var response = given().get("/q/health/ready");
        var cspHeaders = response.headers().getValues("Content-Security-Policy");
        Assertions.assertEquals(1, cspHeaders.size(),
                "Expected exactly 1 CSP header on /q/health/ready but got " + cspHeaders.size()
                        + ": " + cspHeaders);
        var csp = cspHeaders.getFirst();
        Assertions.assertTrue(csp.contains("script-src 'self'"),
                "Non-Swagger CSP must contain strict script-src: " + csp);
        // Extract just the script-src directive — style-src also has 'unsafe-inline'
        var scriptSrc = extractDirective(csp, "script-src");
        Assertions.assertFalse(scriptSrc.contains("'unsafe-inline'"),
                "Non-Swagger script-src must NOT allow 'unsafe-inline': " + scriptSrc);
        // The Manager's update check reads api.github.com from the browser. Without
        // this source the browser refuses the request before it leaves the page, and
        // the rejection is indistinguishable from an unreachable host — so tightening
        // this back kills the feature quietly rather than loudly.
        var connectSrc = extractDirective(csp, "connect-src");
        Assertions.assertTrue(allowsSource(connectSrc, "https://api.github.com"),
                "Non-Swagger connect-src must allow the Manager's release check: " + connectSrc);
    }

    /**
     * Extracts a single CSP directive value (e.g. "script-src 'self'") from a full
     * CSP string.
     */
    private static String extractDirective(String csp, String directive) {
        for (var part : csp.split(";")) {
            var trimmed = part.trim();
            var tokens = trimmed.split("\\s+", 2);
            if (tokens.length > 0 && tokens[0].equals(directive)) {
                return trimmed;
            }
        }
        return "";
    }

    /**
     * Whether a CSP directive lists exactly this source. Sources are
     * whitespace-delimited, and equality is the only safe test on the permissive
     * side: a substring match would also accept https://api.github.com.evil, which
     * is a different host permitting nothing we want. The prohibitive assertions
     * stay substring checks, where matching more broadly is stricter.
     */
    private static boolean allowsSource(String directive, String source) {
        for (var token : directive.trim().split("\\s+")) {
            if (token.equals(source)) {
                return true;
            }
        }
        return false;
    }

    // ==================== Coordinator Admin ====================

    @Test
    @Order(7)
    @DisplayName("Coordinator dashboard should return status")
    void coordinatorDashboard() {
        given().get("/administration/coordinator/status")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Extension Store ====================

    @Test
    @Order(8)
    @DisplayName("Extension store should list available workflow step types")
    void listExtensions() {
        given().get("/extensionstore/extensions")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("$", not(empty()));
    }

    // ==================== Deployment Status ====================

    @Test
    @Order(9)
    @DisplayName("Deployment status list should be accessible")
    void deploymentStatusList() {
        given().get("/administration/production/deploymentstatus")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }
}
