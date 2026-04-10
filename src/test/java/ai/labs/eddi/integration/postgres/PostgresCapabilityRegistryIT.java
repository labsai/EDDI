package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.CapabilityRegistryIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Runs all Capability Registry tests against PostgreSQL. */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresCapabilityRegistryIT extends CapabilityRegistryIT {
}
