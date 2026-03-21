package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.BotEngineIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Agent Engine tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresBotEngineIT extends BotEngineIT {
}
