/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import java.io.IOException;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sends each hop on the engine its profile is configured for
 * ({@code eddi.http.outbound.<profile>.engine}).
 */
final class ProfileRoutedEngine implements OutboundEngine {

    private final Map<Profile, OutboundEngine> engines;

    ProfileRoutedEngine(Map<Profile, OutboundEngine> engines) {
        this.engines = new EnumMap<>(engines);
        for (Profile profile : Profile.values()) {
            if (!this.engines.containsKey(profile)) {
                throw new IllegalArgumentException("no engine for profile " + profile);
            }
        }
    }

    @Override
    public Received send(Hop hop) throws IOException, InterruptedException {
        return engines.get(hop.profile()).send(hop);
    }

    @Override
    public void close() {
        Set<OutboundEngine> distinct = new LinkedHashSet<>(engines.values());
        distinct.forEach(OutboundEngine::close);
    }
}
