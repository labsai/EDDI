/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

/**
 * Test-side access to {@link PostgresSchema}'s per-JVM state for store tests in
 * other packages; production code cannot reach it.
 */
public final class PostgresSchemaTestSupport {

    private PostgresSchemaTestSupport() {
    }

    /** Forgets every completed and failed schema step (and the back-off). */
    public static void reset() {
        PostgresSchema.resetForTests();
    }
}
