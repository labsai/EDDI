package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.ApiContractIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all API contract tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresApiContractIT extends ApiContractIT {
}
