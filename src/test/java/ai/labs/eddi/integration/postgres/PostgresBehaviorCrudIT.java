package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.BehaviorCrudIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Behavior CRUD tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresBehaviorCrudIT extends BehaviorCrudIT {
}
