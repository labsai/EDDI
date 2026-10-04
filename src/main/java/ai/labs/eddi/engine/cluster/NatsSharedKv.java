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
import io.nats.client.api.KeyValueWatchOption;
import io.nats.client.api.KeyValueWatcher;
import io.nats.client.impl.NatsKeyValueWatchSubscription;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.nats.client.api.MessageInfo;
import io.nats.client.impl.Headers;

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

    /**
     * A failed call drops the cached handle. jnats decides at open time whether a
     * handle reads with direct get; a handle opened before another node turned
     * direct get off (see {@code NatsSharedStateFactory.leaderReads}) gets no
     * answer to its reads from then on. Reopening on the next call makes that one
     * failed call the whole cost, not every call until the connection drops.
     */
    private void forgetHandle() {
        kv = null;
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
            forgetHandle();
            outcome = "error";
            throw new ClusterUnavailableException("KV " + op + " on " + bucket + " failed: " + e.getMessage(), e);
        } catch (IOException e) {
            forgetHandle();
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
            forgetHandle();
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
            return Optional.of(versioned(entry));
        });
    }

    /**
     * The stream's last message for the key through the JetStream API's message
     * get, which the stream leader answers — unlike the KV direct get, which any
     * replica may answer. A delete or purge marker reads as absent.
     */
    @Override
    public Optional<Versioned> getConsistent(String key) {
        return run("get-consistent", kv -> {
            MessageInfo info;
            try {
                info = connections.jetStreamManagement().getLastMessage("KV_" + bucket, "$KV." + bucket + "." + key);
            } catch (JetStreamApiException e) {
                if (JetStreamDeadLetterStore.notFound(e)) {
                    return Optional.empty();
                }
                throw e;
            }
            if (info == null || !info.isMessage() || info.getData() == null || info.getData().length == 0) {
                return Optional.empty();
            }
            Headers headers = info.getHeaders();
            String operation = headers == null ? null : headers.getFirst("KV-Operation");
            if (operation != null && !"PUT".equals(operation)) {
                return Optional.empty();
            }
            return Optional.of(new Versioned(info.getData(), info.getSeq(),
                    info.getTime() == null ? 0L : info.getTime().toInstant().toEpochMilli()));
        });
    }

    private static Versioned versioned(KeyValueEntry entry) {
        return new Versioned(entry.getValue(), entry.getRevision(),
                entry.getCreated() == null ? 0L : entry.getCreated().toInstant().toEpochMilli());
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

    /**
     * One ordered watch over the bucket's current values instead of a request per
     * key: the server delivers every live entry, then signals the end of the
     * initial data. Bounded by {@code max} entries and twice the request timeout.
     */
    @Override
    public List<Entry> entries(String prefix, int max) {
        return run("entries", kv -> {
            List<Entry> entries = new ArrayList<>();
            CountDownLatch done = new CountDownLatch(1);
            KeyValueWatcher watcher = new KeyValueWatcher() {
                @Override
                public void watch(KeyValueEntry entry) {
                    if (entry.getOperation() == KeyValueOperation.PUT && entry.getValue() != null
                            && (prefix == null || prefix.isEmpty() || entry.getKey().startsWith(prefix))) {
                        synchronized (entries) {
                            if (entries.size() < max) {
                                entries.add(new Entry(entry.getKey(), versioned(entry)));
                            }
                        }
                    }
                }

                @Override
                public void endOfData() {
                    done.countDown();
                }
            };
            NatsKeyValueWatchSubscription subscription = kv.watchAll(watcher, KeyValueWatchOption.IGNORE_DELETE);
            try {
                if (!done.await(connections.config().natsRequestTimeout().toMillis() * 2, TimeUnit.MILLISECONDS)) {
                    throw new IOException("listing " + bucket + " did not complete in time");
                }
            } finally {
                subscription.unsubscribe();
            }
            synchronized (entries) {
                return List.copyOf(entries);
            }
        });
    }
}
