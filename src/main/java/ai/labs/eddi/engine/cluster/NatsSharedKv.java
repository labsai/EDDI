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

import io.nats.client.PublishOptions;
import io.nats.client.api.MessageInfo;
import io.nats.client.api.PublishAck;
import io.nats.client.impl.Headers;
import io.nats.client.impl.NatsMessage;

/**
 * {@link ISharedKv} on a NATS JetStream KV bucket.
 * <p>
 * The bucket handle is opened lazily and re-opened after a reconnect. A "wrong
 * last sequence" answer (API error 10071, or 10164 for a write that expected
 * the key not to exist yet) is the server telling us a create found the key or
 * a compare-and-set lost — returned as a conflict, not thrown. Every other
 * failure is a {@link ClusterUnavailableException}, so a caller can apply its
 * degraded policy at once.
 */
public class NatsSharedKv implements ISharedKv {

    /** JetStream "wrong last sequence": the key exists, or the revision moved. */
    static final int WRONG_LAST_SEQUENCE = 10071;
    /**
     * The same answer to a write that expected the key not to exist yet (a create).
     * The loser of two concurrent creates of one key gets it (seen live, 8 of 15
     * races): before it was recognised, losing that race — two nodes racing for one
     * lease — was reported as NATS being unreachable.
     */
    static final int WRONG_LAST_SEQUENCE_CONSTANT = 10164;

    /**
     * Whether the server refused a write because the key exists or its revision
     * moved.
     */
    static boolean isConflict(JetStreamApiException e) {
        return e.getApiErrorCode() == WRONG_LAST_SEQUENCE || e.getApiErrorCode() == WRONG_LAST_SEQUENCE_CONSTANT;
    }

    /**
     * The youngest handle a failed call may drop. A slow server times out every
     * call in flight at once; without this each failure would reopen the bucket
     * (one STREAM.INFO round trip each) while it is still slow.
     */
    static final long MIN_HANDLE_AGE_NANOS = TimeUnit.SECONDS.toNanos(1);
    /** How long a failed reopen is not retried; callers fail fast meanwhile. */
    static final long REOPEN_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

    private final NatsConnectionManager connections;
    private final String bucket;
    private final MeterRegistry meterRegistry;
    private final Object reopenLock = new Object();
    private volatile Handle handle;
    /** {@code System.nanoTime()} of the last failed open, or {@code null}. */
    private volatile Long openFailedAt;

    /** An open bucket handle and the connection it was opened on. */
    private record Handle(KeyValue kv, Object connection, long openedAt) {
    }

    private final long minHandleAgeNanos;

    public NatsSharedKv(NatsConnectionManager connections, String bucket, MeterRegistry meterRegistry) {
        this(connections, bucket, meterRegistry, MIN_HANDLE_AGE_NANOS);
    }

    /** With the minimum age of a handle a failure may drop; for tests. */
    NatsSharedKv(NatsConnectionManager connections, String bucket, MeterRegistry meterRegistry, long minHandleAgeNanos) {
        this.connections = connections;
        this.bucket = bucket;
        this.meterRegistry = meterRegistry;
        this.minHandleAgeNanos = minHandleAgeNanos;
    }

    @Override
    public String bucket() {
        return bucket;
    }

    /**
     * The bucket handle, (re)opened once per connection — and only by one caller:
     * the others wait for that open instead of each sending their own STREAM.INFO.
     * A failed open is not retried for {@link #REOPEN_BACKOFF_NANOS}, so a burst of
     * callers against a struggling server fails fast instead of piling opens onto
     * it.
     */
    private Handle kv() {
        Object current = connections.requireConnected();
        Handle h = handle;
        if (h != null && h.connection() == current) {
            return h;
        }
        synchronized (reopenLock) {
            h = handle;
            if (h != null && h.connection() == current) {
                return h;
            }
            Long failedAt = openFailedAt;
            if (failedAt != null && System.nanoTime() - failedAt < REOPEN_BACKOFF_NANOS) {
                throw new ClusterUnavailableException("KV bucket " + bucket + " could not be opened a moment ago; not retried yet");
            }
            KeyValue opened;
            try {
                opened = connections.keyValue(bucket);
            } catch (RuntimeException e) {
                openFailedAt = System.nanoTime();
                throw e;
            }
            openFailedAt = null;
            h = new Handle(opened, current, System.nanoTime());
            handle = h;
            if (meterRegistry != null) {
                meterRegistry.counter("eddi.cluster.kv.reopens", "bucket", bucket).increment();
            }
            return h;
        }
    }

    /**
     * A failed call drops the handle it used — not one another caller has opened
     * since, and not one younger than {@link #MIN_HANDLE_AGE_NANOS} (by default).
     * jnats decides at open time whether a handle reads with direct get; a handle
     * opened before another node turned direct get off (see
     * {@code NatsSharedStateFactory.leaderReads}) gets no answer to its reads from
     * then on. Reopening on the next call makes that one failed call the whole
     * cost, not every call until the connection drops — and only one reopen per
     * stale handle, however many calls failed on it at once.
     */
    private void forgetHandle(Handle failed) {
        if (failed != null && handle == failed && System.nanoTime() - failed.openedAt() >= minHandleAgeNanos) {
            synchronized (reopenLock) {
                if (handle == failed) {
                    handle = null;
                }
            }
        }
    }

    @FunctionalInterface
    private interface KvCall<T> {
        T call(KeyValue kv) throws IOException, JetStreamApiException, InterruptedException;
    }

    private <T> T run(String op, KvCall<T> call) {
        long start = System.nanoTime();
        String outcome = "ok";
        Handle used = null;
        try {
            used = kv();
            return call.call(used.kv());
        } catch (JetStreamApiException e) {
            forgetHandle(used);
            outcome = "error";
            throw new ClusterUnavailableException("KV " + op + " on " + bucket + " failed: " + e.getMessage(), e);
        } catch (IOException e) {
            forgetHandle(used);
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
            // jnats raises IllegalStateException on a closed connection and on a full
            // outgoing queue. Neither makes the handle stale: a new connection gets a new
            // handle anyway (kv() compares the connection), and reopening because the
            // queue is full would only add to it.
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
                if (isConflict(e)) {
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
                info = connections.jetStreamManagement().getLastMessage(streamName(), subject(key));
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

    private String streamName() {
        return "KV_" + bucket;
    }

    private String subject(String key) {
        return "$KV." + bucket + "." + key;
    }

    /**
     * The key's last message, from the stream leader: a value, or the revision of
     * the delete (or purge) marker that removed it. One round trip; the revision of
     * a marker lets the next writer recreate the key with a single compare-and-set
     * instead of the three round trips of a {@code create} on a deleted key (write,
     * conflict, read the marker, write again).
     */
    @Override
    public Latest latest(String key) {
        return run("latest", kv -> {
            MessageInfo info;
            try {
                info = connections.jetStreamManagement().getLastMessage(streamName(), subject(key));
            } catch (JetStreamApiException e) {
                if (JetStreamDeadLetterStore.notFound(e)) {
                    return new Latest(null, 0);
                }
                throw e;
            }
            if (info == null || !info.isMessage()) {
                return new Latest(null, 0);
            }
            Headers headers = info.getHeaders();
            String operation = headers == null ? null : headers.getFirst("KV-Operation");
            if (operation != null && !"PUT".equals(operation)) {
                return new Latest(null, info.getSeq());
            }
            byte[] data = info.getData() == null ? new byte[0] : info.getData();
            return new Latest(new Versioned(data, info.getSeq(), info.getTime() == null ? 0L : info.getTime().toInstant().toEpochMilli()),
                    info.getSeq());
        });
    }

    /**
     * The same delete marker jnats' {@code delete(key, expectedRevision)} writes
     * ({@code KV-Operation: DEL}, guarded by the expected last subject sequence),
     * published directly so the acknowledgement's sequence — the marker's revision
     * — is not thrown away. Every client, of any EDDI version, reads it as a
     * deleted key.
     */
    @Override
    public OptionalLong deleteReturningRevision(String key, long expectedRevision) {
        return run("delete", kv -> {
            Headers headers = new Headers().put("KV-Operation", "DEL");
            try {
                PublishAck ack = connections.jetStream().publish(
                        NatsMessage.builder().subject(subject(key)).headers(headers).data((byte[]) null).build(),
                        PublishOptions.builder().expectedLastSubjectSequence(expectedRevision).build());
                return OptionalLong.of(ack.getSeqno());
            } catch (JetStreamApiException e) {
                if (isConflict(e)) {
                    countConflict();
                    return OptionalLong.empty();
                }
                throw e;
            }
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
                if (isConflict(e)) {
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
                if (isConflict(e)) {
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
