/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.AuditAndSecurityIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Runs all Audit and Security tests against PostgreSQL. */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresAuditAndSecurityIT extends AuditAndSecurityIT {
}
