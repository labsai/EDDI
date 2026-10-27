/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.AgentTestSuiteIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all agent test harness tests against PostgreSQL: suites, run records and
 * the purge of test conversations go through the PostgreSQL stores.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresAgentTestSuiteIT extends AgentTestSuiteIT {
}
