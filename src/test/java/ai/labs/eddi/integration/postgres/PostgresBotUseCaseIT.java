package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.BotUseCaseIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Bot Use Case tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresBotUseCaseIT extends BotUseCaseIT {
}
