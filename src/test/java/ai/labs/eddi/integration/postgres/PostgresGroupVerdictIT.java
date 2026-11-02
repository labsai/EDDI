/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.GroupVerdictIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs the VERDICT convergence and TASK_FORCE rework tests against PostgreSQL,
 * including the persisted {@code taskList.reworkCounts}.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresGroupVerdictIT extends GroupVerdictIT {
}
