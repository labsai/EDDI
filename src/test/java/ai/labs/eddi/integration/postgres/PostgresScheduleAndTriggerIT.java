/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import ai.labs.eddi.integration.ScheduleAndTriggerIT;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Runs all Schedule and Trigger tests against PostgreSQL. */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresScheduleAndTriggerIT extends ScheduleAndTriggerIT {
}
