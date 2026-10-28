/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integration;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link IntegrationTestProfile} with a one-second schedule poll, so the
 * self-continuation ITs see the real poller fire a continuation within a second
 * or two instead of the default 15 s.
 */
public class ContinuationTestProfile extends IntegrationTestProfile {

    /** The poll interval the continuation ITs run with. */
    static final String POLL_INTERVAL = "1s";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("eddi.schedule.poll-interval", POLL_INTERVAL);
        return overrides;
    }
}
