package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.AgentGroupCrudIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Agent Group CRUD tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresAgentGroupCrudIT extends AgentGroupCrudIT {
}
