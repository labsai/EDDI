package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.GroupConversationIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Runs all Group Conversation tests against PostgreSQL. */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresGroupConversationIT extends GroupConversationIT {
}
