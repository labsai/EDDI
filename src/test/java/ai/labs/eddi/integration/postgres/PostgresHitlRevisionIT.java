/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.HitlRevisionIT;
import ai.labs.eddi.integration.PostgresIntegrationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/**
 * Runs the {@code onRejection: REVISE} tests against PostgreSQL: the revision
 * candidate must survive the JSON document store across the pause exactly as it
 * survives the Mongo one.
 */
@QuarkusTest
@TestProfile(PostgresIntegrationTestProfile.class)
class PostgresHitlRevisionIT extends HitlRevisionIT {
}
