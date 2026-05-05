/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.GlobalVariableCrudIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all Global Variable CRUD tests against PostgreSQL.
 * <p>
 * Uses {@link PostgresIntegrationTestProfile} which activates the
 * {@code postgres} profile, disables MongoDB DevServices, and enables
 * PostgreSQL DevServices (Testcontainers). The
 * {@link ai.labs.eddi.datastore.postgres.PostgresGlobalVariableStore} is the
 * active {@link ai.labs.eddi.configs.variables.IGlobalVariableStore} bean in
 * this profile.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresGlobalVariableCrudIT extends GlobalVariableCrudIT {
}
