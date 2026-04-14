package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.ApiCallsCrudIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all API Calls CRUD tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresApiCallsCrudIT extends ApiCallsCrudIT {
}
