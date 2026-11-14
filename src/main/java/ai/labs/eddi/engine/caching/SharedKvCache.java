/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.ClusterValueRefusedException;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.KvKeys;
import ai.labs.eddi.utils.LogSanitizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A cache whose entries are shared by every node of the cluster, for the few
 * caches that hold state rather than copies: replay nonces, A2A task mappings,
 * paginated tool responses, Slack and webhook event de-duplication and channel
 * thread locks. Used only in cluster mode, only for those names (see
 * {@link CacheFactory}); every other cache stays node-local.
 * <p>
 * <b>Point operations</b> ({@code get}, {@code put}, {@code putIfAbsent},
 * {@code remove}, {@code containsKey}) go to the shared bucket — keys are
 * hashed, so no identifier appears in clear in NATS — and are written through
 * to the node-local cache, which serves as the fallback while NATS is
 * unreachable. {@code putIfAbsent} is a KV {@code create}, i.e. atomic across
 * the cluster. <b>Bulk operations</b> ({@code size}, iteration, {@code clear})
 * see the node-local view only: the shared bucket cannot enumerate original
 * keys.
 * <p>
 * <b>Degraded</b>: with {@code failClosed} (replay nonces under the default
 * {@code eddi.cluster.degraded.nonces=reject}) a write throws
 * {@link ClusterUnavailableException} so the caller can refuse; otherwise the
 * node-local cache answers.
 * <p>
 * <b>Recovery</b>: a write that degraded leaves the value on this node only,
 * and remembers the bucket revision this node last saw for the key (or that it
 * saw the key absent, or that it does not know). On the next read the value is
 * published again only by compare-and-set against that revision, so it reaches
 * the bucket when nobody else wrote since, and is dropped in favour of the
 * bucket's value when someone did (or deleted the key): a stale local copy
 * never overwrites, and never shadows, a newer write. A value the bucket
 * refused as too large is tracked apart and is never retried: it is answered
 * from the node-local copy only while the bucket holds nothing for the key.
 */
public class SharedKvCache<K, V> implements ICache<K, V> {

    private static final Logger LOGGER = Logger.getLogger(SharedKvCache.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    /**
     * The exact value classes the shared caches store (see
     * {@link CacheFactory#SHARED_BUCKETS} and their callers): nonce, erasing and
     * de-duplication flags, A2A context ids, A2A task records and principal
     * indexes, paginated tool responses and channel thread targets. The class name
     * is read from the NATS bucket and loaded, so it is matched exactly: the
     * package-prefix list this replaced admitted any class in {@code java.util.}
     * and {@code java.lang.} — {@code java.util.logging.FileHandler} among them —
     * for whoever could write the bucket (security review 2026-10-17, L15). A new
     * shared cache with a new value type must be added here; a value of any other
     * class is refused on read and logged.
     */
    static final Set<String> ALLOWED_VALUE_CLASSES = Set.of(
            "java.lang.Boolean",
            "java.lang.String",
            "ai.labs.eddi.engine.a2a.A2AModels$A2ATaskRecord",
            "ai.labs.eddi.engine.a2a.CachedA2ATaskStore$PrincipalIndex",
            "ai.labs.eddi.modules.llm.tools.PaginatedResponseStore$PaginatedResponse",
            "ai.labs.eddi.configs.channels.model.ChannelTarget");

    /**
     * Most keys remembered as node-local only; beyond it a refused value is simply
     * not found again.
     */
    private static final int MAX_LOCAL_ONLY = 10_000;
    /** Marker baseline: the revision this node last saw is not known. */
    private static final long UNKNOWN = -1L;
    /** Marker baseline: this node last saw the key absent. */
    private static final long ABSENT = 0L;

    private final ICache<K, V> local;
    private final ISharedKv shared;
    private final boolean failClosed;
    /**
     * Hashes of the keys whose latest value the shared bucket did not take (NATS
     * was unreachable, or the value is larger than its payload limit) and which
     * therefore exist on this node only. A read that finds nothing in the bucket
     * answers from the node-local copy for exactly these keys — never for a key
     * another node removed.
     */
    private final Map<String, Long> localOnly = new ConcurrentHashMap<>();
    /**
     * Hashes of the keys whose latest value the bucket refused as too large. Never
     * retried; answers a read only while the bucket holds nothing for the key.
     */
    private final Set<String> tooLarge = ConcurrentHashMap.newKeySet();
    /**
     * The bucket revision this node last saw per key (0 = seen absent), bounded and
     * least-recently-used: an evicted key is "unknown", which recovery treats
     * conservatively.
     */
    private final Map<String, Long> seen = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_LOCAL_ONLY;
        }
    });

    public SharedKvCache(ICache<K, V> local, ISharedKv shared, boolean failClosed) {
        this.local = local;
        this.shared = shared;
        this.failClosed = failClosed;
    }

    private static String key(Object key) {
        return KvKeys.hashed(String.valueOf(key));
    }

    private byte[] encode(V value) {
        ObjectNode node = JSON.createObjectNode();
        node.put("t", value.getClass().getName());
        node.set("v", JSON.valueToTree(value));
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot serialize shared cache value", e);
        }
    }

    @SuppressWarnings("unchecked")
    private V decode(byte[] bytes) {
        try {
            JsonNode node = JSON.readTree(bytes);
            String type = node.path("t").asText("");
            if (!ALLOWED_VALUE_CLASSES.contains(type)) {
                LOGGER.errorf("Refused a shared cache value in %s naming class '%s', which no shared cache stores", shared.bucket(),
                        LogSanitizer.sanitize(type.length() > 200 ? type.substring(0, 200) : type));
                return null;
            }
            return (V) JSON.treeToValue(node.get("v"), Class.forName(type, false, SharedKvCache.class.getClassLoader()));
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            LOGGER.debugf("Unreadable shared cache value in %s: %s", shared.bucket(), e.getMessage());
            return null;
        }
    }

    private void degraded(ClusterUnavailableException e) {
        if (failClosed) {
            throw e;
        }
        LOGGER.debugf("Shared cache %s unavailable, using the node-local copy: %s", shared.bucket(), e.getMessage());
    }

    private void saw(String hash, long revision) {
        seen.put(hash, revision);
    }

    private long lastSeen(String hash) {
        Long revision = seen.get(hash);
        return revision == null ? UNKNOWN : revision;
    }

    /**
     * The shared write failed: the value now lives on this node only. A value the
     * bucket refused as too large is remembered apart and never retried; any other
     * failure is remembered with the revision this node last saw
     * ({@code baseline}), which the republish must still find.
     */
    private void degradedWrite(Object key, ClusterUnavailableException e, long baseline) {
        degraded(e);
        String hash = key(key);
        if (e instanceof ClusterValueRefusedException) {
            localOnly.remove(hash);
            if (tooLarge.size() < MAX_LOCAL_ONLY) {
                tooLarge.add(hash);
            }
            return;
        }
        tooLarge.remove(hash);
        if (localOnly.containsKey(hash)) {
            // Nothing reached the bucket since the first degraded write: its baseline
            // stands.
            return;
        }
        if (localOnly.size() < MAX_LOCAL_ONLY) {
            localOnly.put(hash, baseline);
        }
    }

    /**
     * A write reached the bucket at {@code revision}: nothing about the key is
     * node-local any more.
     */
    private void wrote(String hash, long revision) {
        saw(hash, revision);
        localOnly.remove(hash);
        tooLarge.remove(hash);
    }

    @Override
    public String getCacheName() {
        return local.getCacheName();
    }

    @Override
    public V get(Object key) {
        String hash = key(key);
        try {
            Optional<ISharedKv.Versioned> v = shared.get(hash);
            Long baseline = localOnly.get(hash);
            if (baseline != null) {
                return reconcile(key, hash, baseline, v);
            }
            if (v.isPresent()) {
                saw(hash, v.get().revision());
                return decode(v.get().value());
            }
            saw(hash, ABSENT);
            if (!tooLarge.isEmpty() && tooLarge.contains(hash)) {
                // The bucket refused this value as too large (a paginated tool response above
                // the NATS payload limit): this node's copy is the only one.
                V mine = local.get(key);
                if (mine == null) {
                    tooLarge.remove(hash);
                }
                return mine;
            }
            return null;
        } catch (ClusterUnavailableException e) {
            degraded(e);
            return local.get(key);
        }
    }

    /**
     * Reads a key this node wrote while the bucket would not take it. That value is
     * newer than the bucket's only if nobody wrote since, so it is published again
     * by compare-and-set against the revision this node last saw — never by an
     * unconditional put, which would overwrite a newer write or resurrect a deleted
     * key:
     * <ul>
     * <li>the bucket is still at that revision (or the key still absent, as it was
     * seen): the local value is published and answers the read;</li>
     * <li>the revision moved on, or the key was deleted since: another node wrote
     * after this one, so the marker and the local copy give way to the bucket;</li>
     * <li>the revision was never known: the bucket's value wins when there is one,
     * and an absent key is created;</li>
     * <li>the bucket cannot be written: the marker stays and the local value
     * answers.</li>
     * </ul>
     * Without the republish a status written {@code processing} while NATS was up
     * and {@code completed} while it was down kept reading back {@code processing}
     * after recovery, even on the node that wrote both.
     */
    private V reconcile(Object key, String hash, long baseline, Optional<ISharedKv.Versioned> v) {
        V mine = local.get(key);
        if (mine == null) {
            localOnly.remove(hash);
            return v.map(versioned -> adopt(key, hash, versioned)).orElse(null);
        }
        try {
            OptionalLong written;
            if (v.isPresent()) {
                if (baseline == UNKNOWN || v.get().revision() != baseline) {
                    return adopt(key, hash, v.get());
                }
                written = shared.update(hash, encode(mine), baseline);
            } else if (baseline == ABSENT || baseline == UNKNOWN) {
                written = shared.create(hash, encode(mine));
            } else {
                // Seen at a revision, gone now: deleted (or expired) since.
                localOnly.remove(hash);
                local.remove(key);
                saw(hash, ABSENT);
                return null;
            }
            if (written.isPresent()) {
                wrote(hash, written.getAsLong());
                return mine;
            }
            // Lost the compare-and-set: somebody wrote between the read and now.
            localOnly.remove(hash);
            return shared.getConsistent(hash).map(versioned -> adopt(key, hash, versioned)).orElse(null);
        } catch (ClusterUnavailableException e) {
            // still unreachable, or too large for the bucket: stays node-local
            LOGGER.debugf("Shared cache %s still refuses a node-local value: %s", shared.bucket(), e.getMessage());
            degradedWrite(key, e, baseline);
            return mine;
        }
    }

    /** The bucket's value replaces this node's copy and the marker. */
    @SuppressWarnings("unchecked")
    private V adopt(Object key, String hash, ISharedKv.Versioned versioned) {
        localOnly.remove(hash);
        saw(hash, versioned.revision());
        V value = decode(versioned.value());
        if (value != null) {
            local.put((K) key, value);
        }
        return value;
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public V put(K key, V value) {
        local.put(key, value);
        String hash = key(key);
        try {
            wrote(hash, shared.put(hash, encode(value)));
        } catch (ClusterUnavailableException e) {
            degradedWrite(key, e, lastSeen(hash));
        }
        return null;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        try {
            String hash = key(key);
            OptionalLong created = shared.create(hash, encode(value));
            if (created.isPresent()) {
                local.put(key, value);
                // The bucket holds this value now: a marker left by an earlier degraded
                // write must not later answer for it from the node-local copy.
                wrote(hash, created.getAsLong());
                return null;
            }
            // The create lost: the key exists in the bucket. Report it as present even
            // when the stored value cannot be read back (expired since, or a type the
            // decoder refuses) — the callers are replay nonces and Slack event
            // de-duplication, for which "present" is the fail-closed answer; null would
            // accept a replay.
            V existing = get(key);
            return existing != null ? existing : value;
        } catch (ClusterUnavailableException e) {
            degraded(e);
            return local.putIfAbsent(key, value);
        }
    }

    @Override
    public V remove(Object key) {
        V previous = local.remove(key);
        String hash = key(key);
        localOnly.remove(hash);
        tooLarge.remove(hash);
        try {
            shared.delete(hash);
            saw(hash, ABSENT);
        } catch (ClusterUnavailableException e) {
            degraded(e);
        }
        return previous;
    }

    @Override
    public V put(K key, V value, long lifespan, TimeUnit unit) {
        // The bucket's TTL bounds every entry; the per-entry lifespan applies locally.
        local.put(key, value, lifespan, unit);
        String hash = key(key);
        try {
            wrote(hash, shared.put(hash, encode(value)));
        } catch (ClusterUnavailableException e) {
            degradedWrite(key, e, lastSeen(hash));
        }
        return null;
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
        return putIfAbsent(key, value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
        map.forEach((k, v) -> put(k, v, lifespan, unit));
    }

    @Override
    public V replace(K key, V value, long lifespan, TimeUnit unit) {
        return replace(key, value);
    }

    @Override
    public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
        return replace(key, oldValue, value);
    }

    @Override
    public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return put(key, value, lifespan, lifespanUnit);
    }

    @Override
    public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
        return putIfAbsent(key, value);
    }

    @Override
    public boolean remove(Object key, Object value) {
        V current = get(key);
        if (current != null && current.equals(value)) {
            remove(key);
            return true;
        }
        return false;
    }

    /**
     * A real compare-and-set on the bucket: the value is read with its revision and
     * replaced only while the key is still at that revision, so two nodes (or two
     * threads) racing on one key cannot both win. Was get-then-put, which lost one
     * of two concurrent read-modify-write updates — the A2A principal index relies
     * on this being atomic. While the bucket is unreachable the node-local copy is
     * updated instead and the key remembered as node-local, like a degraded put.
     */
    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        Optional<ISharedKv.Versioned> versioned;
        try {
            versioned = shared.get(key(key));
        } catch (ClusterUnavailableException e) {
            // Nothing could be compared against: only a node-local copy can decide.
            degraded(e);
            boolean replaced = local.replace(key, oldValue, newValue);
            if (replaced) {
                degradedWrite(key, e, lastSeen(key(key)));
            }
            return replaced;
        }
        if (versioned.isEmpty()) {
            return false;
        }
        V current = decode(versioned.get().value());
        if (current == null || !current.equals(oldValue)) {
            return false;
        }
        try {
            OptionalLong updated = shared.update(key(key), encode(newValue), versioned.get().revision());
            if (updated.isEmpty()) {
                return false;
            }
            local.put(key, newValue);
            wrote(key(key), updated.getAsLong());
            return true;
        } catch (ClusterUnavailableException e) {
            // The comparison succeeded against the bucket's own value and only the
            // write failed: the replacement is taken node-locally, exactly like a
            // degraded put — and with the whole value, so a caller that rebuilt
            // newValue from the bucket's current value keeps it. Falling back to
            // local.replace here was wrong: a node without a local copy got false, and
            // a caller retrying from the (now empty) local copy rebuilt the value from
            // nothing.
            local.put(key, newValue);
            degradedWrite(key, e, versioned.get().revision());
            return true;
        }
    }

    @Override
    public V replace(K key, V value) {
        V current = get(key);
        if (current != null) {
            put(key, value);
        }
        return current;
    }

    // ---- node-local view ----

    @Override
    public int size() {
        return local.size();
    }

    @Override
    public int sizeExact() {
        return local.sizeExact();
    }

    @Override
    public boolean isEmpty() {
        return local.isEmpty();
    }

    @Override
    public boolean containsValue(Object value) {
        return local.containsValue(value);
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> m) {
        m.forEach(this::put);
    }

    @Override
    public void clear() {
        local.clear();
    }

    @Override
    public Set<K> keySet() {
        return local.keySet();
    }

    @Override
    public Collection<V> values() {
        return local.values();
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return local.entrySet();
    }
}
