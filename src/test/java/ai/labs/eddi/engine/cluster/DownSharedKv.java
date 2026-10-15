/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/** Test double: a shared bucket while NATS is unreachable. */
public class DownSharedKv implements ISharedKv {

    private static ClusterUnavailableException down() {
        return new ClusterUnavailableException("NATS is down (test)");
    }

    @Override
    public String bucket() {
        return "DOWN";
    }

    @Override
    public OptionalLong create(String key, byte[] value) {
        throw down();
    }

    @Override
    public Optional<Versioned> get(String key) {
        throw down();
    }

    @Override
    public OptionalLong update(String key, byte[] value, long expectedRevision) {
        throw down();
    }

    @Override
    public long put(String key, byte[] value) {
        throw down();
    }

    @Override
    public boolean delete(String key, long expectedRevision) {
        throw down();
    }

    @Override
    public void delete(String key) {
        throw down();
    }

    @Override
    public List<String> keys() {
        throw down();
    }
}
