package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.LlmCrudIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all LLM CRUD tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresLlmCrudIT extends LlmCrudIT {
}
