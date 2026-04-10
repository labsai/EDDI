package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import ai.labs.eddi.integration.RagCrudIT;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all RAG CRUD tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresRagCrudIT extends RagCrudIT {
}
