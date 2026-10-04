/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster.rpc;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/** Single node: there is no other node to call. */
@ApplicationScoped
@Typed(LocalClusterRpc.class)
public class LocalClusterRpc implements IClusterRpc {

    @Override
    public Optional<Map<String, Object>> call(String nodeId, String op, Map<String, Object> request) {
        return Optional.empty();
    }

    @Override
    public Map<String, Map<String, Object>> callAll(String op, Map<String, Object> request) {
        return Map.of();
    }

    @Override
    public void handle(String op, Function<Map<String, Object>, Map<String, Object>> handler) {
        // nothing calls in
    }
}
