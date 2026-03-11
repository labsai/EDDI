package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.BotDeploymentComponentIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Bot Deployment tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresBotDeploymentComponentIT extends BotDeploymentComponentIT {
}
