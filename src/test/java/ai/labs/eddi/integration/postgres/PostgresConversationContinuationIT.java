/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.postgres;

import ai.labs.eddi.integration.ConversationContinuationIT;
import ai.labs.eddi.integration.PostgresContinuationTestProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;

/** Runs all self-continuation tests against PostgreSQL. */
@QuarkusTest
@TestProfile(PostgresContinuationTestProfile.class)
class PostgresConversationContinuationIT extends ConversationContinuationIT {
}
