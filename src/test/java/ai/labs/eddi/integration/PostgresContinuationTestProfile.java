/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link PostgresIntegrationTestProfile} (the {@code postgres} datastore) with
 * the one-second schedule poll of {@link ContinuationTestProfile}.
 */
public class PostgresContinuationTestProfile extends PostgresIntegrationTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("eddi.schedule.poll-interval", ContinuationTestProfile.POLL_INTERVAL);
        return overrides;
    }
}
