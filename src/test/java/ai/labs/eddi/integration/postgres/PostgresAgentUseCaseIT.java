package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.AgentUseCaseIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Agent Use Case tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresAgentUseCaseIT extends AgentUseCaseIT {
}
