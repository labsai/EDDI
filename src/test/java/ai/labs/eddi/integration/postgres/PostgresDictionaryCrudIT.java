package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.DictionaryCrudIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Dictionary CRUD tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresDictionaryCrudIT extends DictionaryCrudIT {
}
