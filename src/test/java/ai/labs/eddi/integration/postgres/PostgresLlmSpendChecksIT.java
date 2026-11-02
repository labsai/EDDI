/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.LlmSpendChecksIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs the spend-check tests against PostgreSQL: the stored conversation's
 * {@code audit:cost} and the tenant's monthly counter both live in the
 * database.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresLlmSpendChecksIT extends LlmSpendChecksIT {
}
