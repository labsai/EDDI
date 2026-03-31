package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.AgentDeploymentComponentIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Agent Deployment tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresAgentDeploymentComponentIT extends AgentDeploymentComponentIT {
}
