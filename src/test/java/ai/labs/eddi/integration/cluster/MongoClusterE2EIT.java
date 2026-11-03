/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration.cluster;

import ai.labs.eddi.integration.cluster.ClusterHarness.Db;
import org.junit.jupiter.api.BeforeAll;

/**
 * The cluster end-to-end suite on MongoDB. Opt-in: see
 * {@link AbstractClusterE2EIT}.
 */
class MongoClusterE2EIT extends AbstractClusterE2EIT {

    @BeforeAll
    static void start() {
        startCluster(Db.MONGO);
    }
}
