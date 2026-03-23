package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.ApiContractIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Runs all API contract tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresApiContractIT extends ApiContractIT {

    /**
     * Override: uses a valid UUID (not MongoDB ObjectId) for the non-existent resource.
     * The base test uses "000000000000000000000000" which is not a valid UUID,
     * causing a PostgreSQL UUID cast error (500) instead of 404.
     */
    @Test
    @Override
    @DisplayName("GET non-existent resource should return 404")
    protected void readNonExistent_returns404() {
        given().get("/rulestore/rulesets/00000000-0000-0000-0000-000000000000?version=1")
                .then().assertThat()
                .statusCode(404);
    }
}
