/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.cluster;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import io.nats.client.api.KeyValueOperation;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * {@link ISharedKv} on a NATS JetStream KV bucket.
 * <p>
 * The bucket handle is opened lazily and re-opened after a reconnect. A "wrong
 * last sequence" answer (API error 10071) is the server telling us a create
 * found the key or a compare-and-set lost — returned as a conflict, not thrown.
 * Every other failure is a {@link ClusterUnavailableException}, so a caller can
 * apply its degraded policy at once.
 */
public class NatsSharedKv implements ISharedKv {

    /** JetStream "wrong last sequence": the key exists, or the revision moved. */
    static final int WRONG_LAST_SEQUENCE = 10071;

    private final NatsConnectionManager connections;
    private final String bucket;
    private final MeterRegistry meterRegistry;
    private volatile KeyValue kv;
    private volatile Object kvConnection;

    public NatsSharedKv(NatsConnectionManager connections, String bucket, MeterRegistry meterRegistry) {
        this.connections = connections;
        this.bucket = bucket;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String bucket() {
        return bucket;
    }

    private KeyValue kv() {
        Object current = connections.requireConnected();
        KeyValue handle = kv;
        if (handle == null || kvConnection != current) {
            handle = connections.keyValue(bucket);
            kv = handle;
            kvConnection = current;
        }
        return handle;
    }

    @FunctionalInterface
    private interface KvCall<T> {
        T call(KeyValue kv) throws IOException, JetStreamApiException, InterruptedException;
    }

    private <T> T run(String op, KvCall<T> call) {
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return call.call(kv());
        } catch (JetStreamApiException e) {
            outcome = "error";
            throw new ClusterUnavailableException("KV " + op + " on " + bucket + " failed: " + e.getMessage(), e);
        } catch (IOException e) {
            outcome = "unavailable";
            throw new ClusterUnavailableException("KV " + op + " on " + bucket + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            outcome = "interrupted";
            throw new ClusterUnavailableException("KV " + op + " on " + bucket + " interrupted", e);
        } catch (ClusterUnavailableException e) {
            outcome = "unavailable";
            throw e;
        } catch (RuntimeException e) {
            // jnats raises IllegalStateException on a closed connection.
            outcome = "unavailable";
            throw new ClusterUnavailableException("KV " + op + " on " + bucket + " failed: " + e.getMessage(), e);
        } finally {
            if (meterRegistry != null) {
                Timer.builder("eddi.cluster.kv").tag("bucket", bucket).tag("op", op).tag("outcome", outcome)
                        .register(meterRegistry).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    private void countConflict() {
        if (meterRegistry != null) {
            meterRegistry.counter("eddi.cluster.kv.cas.conflicts", "bucket", bucket).increment();
        }
    }

    @Override
    public OptionalLong create(String key, byte[] value) {
        return run("create", kv -> {
            try {
                return OptionalLong.of(kv.create(key, value));
            } catch (JetStreamApiException e) {
                if (e.getApiErrorCode() == WRONG_LAST_SEQUENCE) {
                    countConflict();
                    return OptionalLong.empty();
                }
                throw e;
            }
        });
    }

    @Override
    public Optional<Versioned> get(String key) {
        return run("get", kv -> {
            KeyValueEntry entry = kv.get(key);
            if (entry == null || entry.getOperation() != KeyValueOperation.PUT || entry.getValue() == null) {
                return Optional.empty();
            }
            return Optional.of(new Versioned(entry.getValue(), entry.getRevision()));
        });
    }

    @Override
    public OptionalLong update(String key, byte[] value, long expectedRevision) {
        return run("update", kv -> {
            try {
                return OptionalLong.of(kv.update(key, value, expectedRevision));
            } catch (JetStreamApiException e) {
                if (e.getApiErrorCode() == WRONG_LAST_SEQUENCE) {
                    countConflict();
                    return OptionalLong.empty();
                }
                throw e;
            }
        });
    }

    @Override
    public long put(String key, byte[] value) {
        return run("put", kv -> kv.put(key, value));
    }

    @Override
    public boolean delete(String key, long expectedRevision) {
        return run("delete", kv -> {
            try {
                kv.delete(key, expectedRevision);
                return true;
            } catch (JetStreamApiException e) {
                if (e.getApiErrorCode() == WRONG_LAST_SEQUENCE) {
                    countConflict();
                    return false;
                }
                throw e;
            }
        });
    }

    @Override
    public void delete(String key) {
        run("delete", kv -> {
            kv.delete(key);
            return null;
        });
    }

    @Override
    public List<String> keys() {
        return run("keys", KeyValue::keys);
    }
}
