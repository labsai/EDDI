package ai.labs.eddi.integration;

import ai.labs.eddi.backup.model.ImportPreview;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.*;

import java.io.File;
import java.io.IOException;
import java.net.URL;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for the import/export merge strategy.
 * <p>
 * Tests the full round-trip: import (create) → export → preview (merge) →
 * re-import (merge), verifying that merge imports update existing resources
 * instead of creating duplicates.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ImportMergeIT extends BaseIntegrationIT {

    // Shared state across ordered tests
    private static ResourceId firstImportAgentId;
    private static String firstImportAgentOriginId;
    private static byte[] exportedZipBytes;

    // ==================== Test 1: Initial import (strategy=create)
    // ====================

    @Test
    @Order(1)
    @DisplayName("import Agent with default strategy should create new resources and set originId")
    void importCreate() throws Exception {
        File agentZip = loadTestZip("weather_agent_v1");

        Response response = given().contentType("application/zip").body(agentZip).post("/backup/import");

        response.then().statusCode(200);

        String location = response.getHeader("location");
        assertThat("Import should return a location header", location, notNullValue());

        firstImportAgentId = extractResourceId(location);
        assertThat("Agent ID should not be null", firstImportAgentId.id(), notNullValue());
        assertThat("Agent version should be 1", firstImportAgentId.version(), equalTo(1));

        // Verify originId was set on the agent's descriptor
        Response descriptorResponse = given()
                .get("/descriptorstore/descriptors/" + firstImportAgentId.id() + "?version=" + firstImportAgentId.version());

        descriptorResponse.then().statusCode(200);

        // Store the originId for later assertions
        firstImportAgentOriginId = descriptorResponse.jsonPath().getString("originId");
        assertThat("originId should be set on the descriptor", firstImportAgentOriginId, notNullValue());
    }

    // ==================== Test 2: Export the imported Agent ====================

    @Test
    @Order(2)
    @DisplayName("export should produce a downloadable zip for the imported agent")
    void exportAgent() {
        assertThat("firstImportAgentId must be set by test 1", firstImportAgentId, notNullValue());

        // Trigger export
        Response exportResponse = given().post("/backup/export/" + firstImportAgentId.id() + "?agentVersion=" + firstImportAgentId.version());

        exportResponse.then().statusCode(200);

        String zipLocation = exportResponse.getHeader("location");
        assertThat("Export should return a location header", zipLocation, notNullValue());

        // Download the zip
        Response downloadResponse = given().get(zipLocation);
        downloadResponse.then().statusCode(200);

        exportedZipBytes = downloadResponse.asByteArray();
        assertThat("Exported zip should not be empty", exportedZipBytes.length, greaterThan(0));
    }

    // ==================== Test 3: Preview merge import ====================

    @Test
    @Order(3)
    @DisplayName("preview import should show UPDATE actions for existing resources")
    void previewMergeImport() {
        assertThat("exportedZipBytes must be set by test 2", exportedZipBytes, notNullValue());

        Response previewResponse = given().contentType("application/zip").body(exportedZipBytes).post("/backup/import/preview");

        previewResponse.then().statusCode(200);

        ImportPreview preview = previewResponse.as(ImportPreview.class);

        assertThat("Preview should identify the agent", preview.agentOriginId(), notNullValue());
        assertThat("Preview should have resources", preview.resources(), not(empty()));

        // Since we already imported this agent, all resources should show UPDATE
        long updateCount = preview.resources().stream().filter(r -> r.action() == ImportPreview.DiffAction.UPDATE).count();
        assertThat("At least some resources should be marked UPDATE", updateCount, greaterThan(0L));

        // The Agent itself should be UPDATE
        boolean agentIsUpdate = preview.resources().stream()
                .anyMatch(r -> "agent".equals(r.resourceType()) && r.action() == ImportPreview.DiffAction.UPDATE);
        assertThat("Agent resource should be UPDATE", agentIsUpdate, is(true));
    }

    // ==================== Test 4: Merge import should reuse the same Agent ID
    // ====================

    @Test
    @Order(4)
    @DisplayName("merge import should update existing Agent instead of creating a duplicate")
    void mergeImportReusesId() {
        assertThat("exportedZipBytes must be set by test 2", exportedZipBytes, notNullValue());
        assertThat("firstImportAgentId must be set by test 1", firstImportAgentId, notNullValue());

        Response mergeResponse = given().contentType("application/zip").queryParam("strategy", "merge").body(exportedZipBytes).post("/backup/import");

        mergeResponse.then().statusCode(200);

        String mergeLocation = mergeResponse.getHeader("location");
        assertThat("Merge import should return a location header", mergeLocation, notNullValue());

        ResourceId mergedAgentId = extractResourceId(mergeLocation);

        // The Agent ID should be the SAME as the first import
        assertThat("Merge should reuse the same Agent ID", mergedAgentId.id(), equalTo(firstImportAgentId.id()));

        // The version should be incremented (updated, not duplicated)
        assertThat("Merge should create a new version", mergedAgentId.version(), greaterThan(firstImportAgentId.version()));
    }

    // ==================== Test 5: Preview after merge should still show UPDATE
    // ====================

    @Test
    @Order(5)
    @DisplayName("preview after merge import should still show UPDATE for all resources")
    void previewAfterMerge() {
        assertThat("exportedZipBytes must be set by test 2", exportedZipBytes, notNullValue());

        Response previewResponse = given().contentType("application/zip").body(exportedZipBytes).post("/backup/import/preview");

        previewResponse.then().statusCode(200);

        ImportPreview preview = previewResponse.as(ImportPreview.class);

        // After merge, everything should still be UPDATE (not CREATE — no duplicates!)
        long createCount = preview.resources().stream().filter(r -> r.action() == ImportPreview.DiffAction.CREATE).count();
        assertThat("No resources should be CREATE after merge (no duplicates)", createCount, equalTo(0L));
    }

    // ==================== Test 6: Selective merge import ====================

    @Test
    @Order(6)
    @DisplayName("selective merge should only update selected resources")
    void selectiveMerge() {
        assertThat("exportedZipBytes must be set by test 2", exportedZipBytes, notNullValue());

        // First get a preview to know which resources exist
        Response previewResponse = given().contentType("application/zip").body(exportedZipBytes).post("/backup/import/preview");

        ImportPreview preview = previewResponse.as(ImportPreview.class);
        assertThat("Preview should have resources", preview.resources(), not(empty()));

        // Select only the Agent resource
        String agentOriginId = preview.resources().stream().filter(r -> "agent".equals(r.resourceType())).map(ImportPreview.ResourceDiff::originId)
                .findFirst().orElseThrow();

        Response selectiveResponse = given().contentType("application/zip").queryParam("strategy", "merge")
                .queryParam("selectedResources", agentOriginId).body(exportedZipBytes).post("/backup/import");

        selectiveResponse.then().statusCode(200);

        String location = selectiveResponse.getHeader("location");
        assertThat("Selective merge should return a location header", location, notNullValue());
    }

    // ==================== Test 7: Second create import should produce different ID
    // ====================

    @Test
    @Order(7)
    @DisplayName("create strategy should always produce a new Agent ID (not merge)")
    void createAlwaysNew() throws Exception {
        File agentZip = loadTestZip("weather_agent_v1");

        Response response = given().contentType("application/zip").body(agentZip).post("/backup/import");

        response.then().statusCode(200);

        String location = response.getHeader("location");
        assertThat("Import should return a location header", location, notNullValue());

        ResourceId secondAgentId = extractResourceId(location);

        // A fresh create import should produce a DIFFERENT Agent ID
        assertThat("Create strategy should produce a new Agent ID (not merge)", secondAgentId.id(), not(equalTo(firstImportAgentId.id())));
    }

    // ==================== Helpers ====================

    private File loadTestZip(String filename) throws IOException {
        URL resource = Thread.currentThread().getContextClassLoader().getResource("tests/useCases/" + filename + ".zip");
        if (resource == null) {
            throw new IOException("Test resource not found: tests/useCases/" + filename + ".zip");
        }
        return new File(resource.getFile().replaceFirst("^/([A-Z]:)", "$1"));
    }
}
