package ai.labs.eddi.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Integration test for the Capability Registry REST endpoints.
 * <p>
 * Tests skill listing and agent-by-skill search via {@code /capabilities}.
 */
@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class CapabilityRegistryIT {

    // ==================== List Skills ====================

    @Test
    @Order(1)
    @DisplayName("List skills should return set")
    void listSkills() {
        given().get("/capabilities/skills")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }

    // ==================== Search by Skill ====================

    @Test
    @Order(2)
    @DisplayName("Search with no matching skill should return empty list")
    void searchBySkill_noMatch() {
        given().queryParam("skill", "nonexistent-skill-xyz-" + System.currentTimeMillis())
                .get("/capabilities")
                .then().assertThat()
                .statusCode(200)
                .body("$", empty());
    }

    @Test
    @Order(3)
    @DisplayName("Search with default strategy should return list")
    void searchBySkill_defaultStrategy() {
        given().queryParam("skill", "test")
                .queryParam("strategy", "highest_confidence")
                .get("/capabilities")
                .then().assertThat()
                .statusCode(200)
                .contentType(ContentType.JSON);
    }
}
