/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Typed;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-node {@link ISharedStateFactory}: every bucket is a process-local map.
 * {@link #isShared()} is {@code false}, which is how components know to keep
 * their original node-local implementation in in-memory mode.
 */
@ApplicationScoped
@Typed(LocalSharedStateFactory.class)
public class LocalSharedStateFactory implements ISharedStateFactory {

    private final Map<String, ISharedKv> buckets = new ConcurrentHashMap<>();

    @Override
    public ISharedKv bucket(SharedBucket spec) {
        return buckets.computeIfAbsent(spec.name(), name -> new InMemorySharedKv(name, spec.ttl()));
    }

    @Override
    public boolean isShared() {
        return false;
    }
}
