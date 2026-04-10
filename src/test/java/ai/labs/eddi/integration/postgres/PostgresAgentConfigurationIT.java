package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.AgentConfigurationIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Runs all Agent Configuration tests against PostgreSQL. */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresAgentConfigurationIT extends AgentConfigurationIT {
}
