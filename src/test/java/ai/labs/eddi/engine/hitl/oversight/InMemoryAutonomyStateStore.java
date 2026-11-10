/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A compare-and-set autonomy store in memory, round-tripping every write
 * through the stores' JSON form — the same serialization both database stores
 * use.
 */
public class InMemoryAutonomyStateStore implements IAutonomyStateStore {

    private final Map<String, String> rows = new TreeMap<>();
    private final Map<String, Long> versions = new TreeMap<>();

    private static String key(String agentId, String category) {
        return agentId + "\u0000" + category;
    }

    @Override
    public synchronized Optional<AutonomyState> read(String agentId, String category) {
        String json = rows.get(key(agentId, category));
        return json == null
                ? Optional.empty()
                : Optional.of(OversightJson.read(json, AutonomyState.class).withVersion(versions.get(key(agentId, category))));
    }

    @Override
    public synchronized List<AutonomyState> list(String agentId, int limit) {
        List<AutonomyState> result = new ArrayList<>();
        for (String k : rows.keySet()) {
            AutonomyState s = OversightJson.read(rows.get(k), AutonomyState.class).withVersion(versions.get(k));
            if (agentId == null || agentId.equals(s.agentId())) {
                result.add(s);
            }
        }
        return result.subList(0, Math.min(limit, result.size()));
    }

    @Override
    public synchronized List<AutonomyState> listSuspended(String agentId) {
        return list(agentId, Integer.MAX_VALUE).stream().filter(AutonomyState::suspended).toList();
    }

    @Override
    public synchronized long countSuspended() {
        return list(null, Integer.MAX_VALUE).stream().filter(AutonomyState::suspended).count();
    }

    @Override
    public synchronized Optional<AutonomyState> save(AutonomyState state, long expectedVersion) {
        String k = key(state.agentId(), state.category());
        long current = versions.getOrDefault(k, 0L);
        if (current != expectedVersion) {
            return Optional.empty();
        }
        AutonomyState stored = state.withVersion(expectedVersion + 1);
        rows.put(k, OversightJson.write(stored));
        versions.put(k, stored.version());
        return Optional.of(stored);
    }
}
