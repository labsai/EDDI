package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import ai.labs.eddi.integration.PromptSnippetCrudIT;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Prompt Snippet CRUD tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresPromptSnippetCrudIT extends PromptSnippetCrudIT {
}
