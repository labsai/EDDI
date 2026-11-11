/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.cluster;

import ai.labs.eddi.integration.cluster.ClusterHarness.Db;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The cluster end-to-end suite on PostgreSQL. Opt-in: see
 * {@link AbstractClusterE2EIT}.
 */
// Repeated here: JUnit does not inherit a class-level condition from the
// abstract base, and without it failsafe runs this suite in every build.
@EnabledIfSystemProperty(named = "eddi.cluster.e2e", matches = "true")
class PostgresClusterE2EIT extends AbstractClusterE2EIT {

    @BeforeAll
    static void start() {
        startCluster(Db.POSTGRES);
    }
}
