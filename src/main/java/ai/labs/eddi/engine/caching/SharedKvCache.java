/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.caching;

import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.ISharedKv;
import ai.labs.eddi.engine.cluster.KvKeys;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * A cache whose entries are shared by every node of the cluster, for the few
 * caches that hold state rather than copies: replay nonces, A2A task mappings,
 * paginated tool responses, Slack event de-duplication and channel thread
 * locks. Used only in cluster mode, only for those names (see
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
 */
public class SharedKvCache<K, V> implements ICache<K, V> {

    private static final Logger LOGGER = Logger.getLogger(SharedKvCache.class);
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    /** Value classes a shared entry may name; anything else is ignored on read. */
    private static final String[] ALLOWED_PACKAGES = {"ai.labs.eddi.", "java.lang.", "java.util."};

    /**
     * Most keys remembered as node-local only; beyond it a refused value is simply
     * not found again.
     */
    private static final int MAX_LOCAL_ONLY = 10_000;

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
    private final Set<String> localOnly = ConcurrentHashMap.newKeySet();

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
            boolean allowed = false;
            for (String prefix : ALLOWED_PACKAGES) {
                allowed |= type.startsWith(prefix);
            }
            if (!allowed) {
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

    /** The shared write failed: the value now lives on this node only. */
    private void degradedWrite(Object key, ClusterUnavailableException e) {
        degraded(e);
        if (localOnly.size() < MAX_LOCAL_ONLY) {
            localOnly.add(key(key));
        }
    }

    @Override
    public String getCacheName() {
        return local.getCacheName();
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(Object key) {
        try {
            Optional<ISharedKv.Versioned> v = shared.get(key(key));
            if (v.isEmpty() && !localOnly.isEmpty() && localOnly.contains(key(key))) {
                // Written while the bucket was unreachable or refused it (a paginated tool
                // response larger than the NATS payload limit): this node's copy is the only
                // one.
                V mine = local.get(key);
                if (mine == null) {
                    localOnly.remove(key(key));
                }
                return mine;
            }
            return v.map(versioned -> decode(versioned.value())).orElse(null);
        } catch (ClusterUnavailableException e) {
            degraded(e);
            return local.get(key);
        }
    }

    @Override
    public boolean containsKey(Object key) {
        return get(key) != null;
    }

    @Override
    public V put(K key, V value) {
        local.put(key, value);
        try {
            shared.put(key(key), encode(value));
            localOnly.remove(key(key));
        } catch (ClusterUnavailableException e) {
            degradedWrite(key, e);
        }
        return null;
    }

    @Override
    public V putIfAbsent(K key, V value) {
        try {
            if (shared.create(key(key), encode(value)).isPresent()) {
                local.put(key, value);
                // The bucket holds this value now: a marker left by an earlier degraded
                // write must not later answer for it from the node-local copy.
                localOnly.remove(key(key));
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
        localOnly.remove(key(key));
        try {
            shared.delete(key(key));
        } catch (ClusterUnavailableException e) {
            degraded(e);
        }
        return previous;
    }

    @Override
    public V put(K key, V value, long lifespan, TimeUnit unit) {
        // The bucket's TTL bounds every entry; the per-entry lifespan applies locally.
        local.put(key, value, lifespan, unit);
        try {
            shared.put(key(key), encode(value));
            localOnly.remove(key(key));
        } catch (ClusterUnavailableException e) {
            degradedWrite(key, e);
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

    @Override
    public boolean replace(K key, V oldValue, V newValue) {
        V current = get(key);
        if (current != null && current.equals(oldValue)) {
            put(key, newValue);
            return true;
        }
        return false;
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
