package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.ConversationServiceComponentIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Conversation Service tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresConversationServiceComponentIT extends ConversationServiceComponentIT {
}
