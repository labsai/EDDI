/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.McpCallsCrudIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs all MCP Calls CRUD tests against PostgreSQL.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresMcpCallsCrudIT extends McpCallsCrudIT {
}
