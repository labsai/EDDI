/* Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.impl;

import ai.labs.eddi.secrets.model.EncryptedDek;
import ai.labs.eddi.secrets.model.EncryptedSecret;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.persistence.ISecretPersistence;
import ai.labs.eddi.secrets.persistence.PersistenceException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A persistence that actually stores things, so a write can be followed by a
 * real decrypt, with the conditional-write semantics of the production stores.
 * Counts the calls some tests assert on.
 * <p>
 * Every read hands back a copy, as a database read does. Handing back the live
 * instance would let a caller mutate storage by accident — and a guarded write
 * would then compare the row against itself and pass for the wrong reason.
 */
final class InMemorySecretPersistence implements ISecretPersistence {

    final Map<String, EncryptedSecret> secrets = new LinkedHashMap<>();
    final Map<String, EncryptedDek> deks = new LinkedHashMap<>();
    final Map<String, String> meta = new LinkedHashMap<>();

    int upsertSecretCalls;
    int updateSecretGrantCalls;
    int findDekCalls;

    /**
     * Runs once, immediately after the next {@link #findSecret} has taken its copy
     * — i.e. between a reader's read and whatever that reader writes next. That is
     * the window a concurrent writer lands in, made deterministic.
     */
    Runnable afterNextFind;

    /** Runs once, before the next {@link #insertDek} — a racing first store. */
    Runnable beforeNextInsertDek;

    /** Runs once, before the next {@link #updateDekWrapping}. */
    Runnable beforeNextDekRewrap;

    /** When set, the next {@link #updateDekWrapping} call throws it. */
    RuntimeException failNextDekRewrap;

    /**
     * Fails every {@link #updateDekWrapping} after this many successful ones; -1 =
     * never.
     */
    int failDekRewrapsAfter = -1;

    private static String key(String tenantId, String keyName) {
        return tenantId + "/" + keyName;
    }

    private static String dekKey(String tenantId, int generation) {
        return tenantId + "/" + generation;
    }

    static EncryptedSecret copyOf(EncryptedSecret s) {
        return new EncryptedSecret(s.getId(), s.getTenantId(), s.getKeyName(), s.getEncryptedValue(), s.getIv(), s.getDekId(), s.getChecksum(),
                s.getDescription(), s.getAllowedAgents() == null ? null : List.copyOf(s.getAllowedAgents()), s.getCreatedAt(), s.getLastAccessedAt(),
                s.getLastRotatedAt());
    }

    static EncryptedDek copyOf(EncryptedDek d) {
        return new EncryptedDek(d.getId(), d.getTenantId(), d.getGeneration(), d.getEncryptedDek(), d.getIv(), d.getCreatedAt());
    }

    /**
     * Mirrors both production stores: a null grant or description is "not supplied"
     * — kept on an update, defaulted on an insert.
     */
    @Override
    public void upsertSecret(EncryptedSecret secret) {
        upsertSecretCalls++;
        EncryptedSecret existing = secrets.get(key(secret.getTenantId(), secret.getKeyName()));
        EncryptedSecret written = copyOf(secret);
        if (existing != null) {
            written.setCreatedAt(existing.getCreatedAt());
            if (secret.getAllowedAgents() == null) {
                written.setAllowedAgents(existing.getAllowedAgents());
            }
            if (secret.getDescription() == null) {
                written.setDescription(existing.getDescription());
            }
        } else if (secret.getAllowedAgents() == null) {
            written.setAllowedAgents(List.of(SecretMetadata.WILDCARD_AGENT));
        }
        secrets.put(key(secret.getTenantId(), secret.getKeyName()), written);
    }

    @Override
    public Optional<EncryptedSecret> findSecret(String tenantId, String keyName) {
        Optional<EncryptedSecret> read = Optional.ofNullable(secrets.get(key(tenantId, keyName))).map(InMemorySecretPersistence::copyOf);
        Runnable hook = afterNextFind;
        afterNextFind = null;
        if (hook != null) {
            hook.run();
        }
        return read;
    }

    @Override
    public boolean deleteSecret(String tenantId, String keyName) {
        return secrets.remove(key(tenantId, keyName)) != null;
    }

    @Override
    public List<EncryptedSecret> listSecretsByTenant(String tenantId) {
        var result = new ArrayList<EncryptedSecret>();
        for (var entry : secrets.entrySet()) {
            if (entry.getValue().getTenantId().equals(tenantId)) {
                result.add(copyOf(entry.getValue()));
            }
        }
        return result;
    }

    @Override
    public boolean updateSecretSealing(EncryptedSecret secret, String expectedDekId) {
        EncryptedSecret stored = secrets.get(key(secret.getTenantId(), secret.getKeyName()));
        if (stored == null || !Objects.equals(stored.getDekId(), expectedDekId)) {
            return false;
        }
        stored.setEncryptedValue(secret.getEncryptedValue());
        stored.setIv(secret.getIv());
        stored.setDekId(secret.getDekId());
        stored.setLastRotatedAt(secret.getLastRotatedAt());
        return true;
    }

    @Override
    public boolean updateSecretGrant(String tenantId, String keyName, List<String> allowedAgents, String description) {
        updateSecretGrantCalls++;
        EncryptedSecret stored = secrets.get(key(tenantId, keyName));
        if (stored == null) {
            return false;
        }
        stored.setAllowedAgents(allowedAgents == null ? null : List.copyOf(allowedAgents));
        stored.setDescription(description);
        return true;
    }

    @Override
    public boolean updateSecretGrantIfUnchanged(String tenantId, String keyName, List<String> expectedAllowedAgents, List<String> allowedAgents,
                                                String description) {
        EncryptedSecret stored = secrets.get(key(tenantId, keyName));
        if (stored == null || !SecretMetadata.sameGrant(stored.getAllowedAgents(), expectedAllowedAgents)) {
            return false;
        }
        return updateSecretGrant(tenantId, keyName, allowedAgents, description);
    }

    @Override
    public void touchLastAccessed(String tenantId, String keyName, Instant lastAccessedAt) {
        EncryptedSecret stored = secrets.get(key(tenantId, keyName));
        if (stored != null) {
            stored.setLastAccessedAt(lastAccessedAt);
        }
    }

    @Override
    public void upsertDek(EncryptedDek dek) {
        deks.put(dekKey(dek.getTenantId(), dek.getGeneration()), copyOf(dek));
    }

    @Override
    public boolean insertDek(EncryptedDek dek) {
        Runnable hook = beforeNextInsertDek;
        beforeNextInsertDek = null;
        if (hook != null) {
            hook.run();
        }
        return deks.putIfAbsent(dekKey(dek.getTenantId(), dek.getGeneration()), copyOf(dek)) == null;
    }

    @Override
    public boolean updateDekWrapping(EncryptedDek dek, String expectedIv) {
        Runnable hook = beforeNextDekRewrap;
        beforeNextDekRewrap = null;
        if (hook != null) {
            hook.run();
        }
        RuntimeException failure = failNextDekRewrap;
        failNextDekRewrap = null;
        if (failure != null) {
            throw failure;
        }
        if (failDekRewrapsAfter == 0) {
            throw new PersistenceException("simulated failure part-way through a KEK rotation");
        }
        EncryptedDek stored = deks.get(dekKey(dek.getTenantId(), dek.getGeneration()));
        if (stored == null || !Objects.equals(stored.getIv(), expectedIv)) {
            return false;
        }
        stored.setEncryptedDek(dek.getEncryptedDek());
        stored.setIv(dek.getIv());
        if (failDekRewrapsAfter > 0) {
            failDekRewrapsAfter--;
        }
        return true;
    }

    @Override
    public boolean deleteDekIfWrappedWith(String tenantId, int generation, String expectedIv) {
        EncryptedDek stored = deks.get(dekKey(tenantId, generation));
        if (stored == null || !Objects.equals(stored.getIv(), expectedIv)) {
            return false;
        }
        deks.remove(dekKey(tenantId, generation));
        return true;
    }

    @Override
    public Optional<EncryptedDek> findDek(String tenantId) {
        findDekCalls++;
        return listDeks(tenantId).stream().reduce((first, second) -> second);
    }

    @Override
    public Optional<EncryptedDek> findDek(String tenantId, int generation) {
        findDekCalls++;
        return Optional.ofNullable(deks.get(dekKey(tenantId, generation))).map(InMemorySecretPersistence::copyOf);
    }

    @Override
    public List<EncryptedDek> listDeks(String tenantId) {
        return deks.values().stream().filter(d -> d.getTenantId().equals(tenantId)).sorted(Comparator.comparingInt(EncryptedDek::getGeneration))
                .map(InMemorySecretPersistence::copyOf).toList();
    }

    @Override
    public void deleteDek(String tenantId) {
        deks.entrySet().removeIf(e -> e.getValue().getTenantId().equals(tenantId));
    }

    @Override
    public List<EncryptedDek> listAllDeks() {
        return deks.values().stream().map(InMemorySecretPersistence::copyOf).toList();
    }

    @Override
    public String getMetaValue(String key) {
        return meta.get(key);
    }

    @Override
    public void setMetaValue(String key, String value) {
        meta.put(key, value);
    }

    /** When set, the next {@link #putMetaValueIfAbsent} for this key throws. */
    String failNextPutIfAbsentFor;

    @Override
    public String putMetaValueIfAbsent(String key, String value) {
        if (key.equals(failNextPutIfAbsentFor)) {
            failNextPutIfAbsentFor = null;
            throw new PersistenceException("simulated failure writing " + key);
        }
        meta.putIfAbsent(key, value);
        return meta.get(key);
    }

    /** When set, the next {@link #deleteMetaValue} call throws it. */
    RuntimeException failNextMetaDelete;

    @Override
    public void deleteMetaValue(String key) {
        RuntimeException failure = failNextMetaDelete;
        failNextMetaDelete = null;
        if (failure != null) {
            throw failure;
        }
        meta.remove(key);
    }

    @Override
    public int deleteMetaValuesWithPrefix(String prefix) {
        int before = meta.size();
        meta.keySet().removeIf(key -> key.startsWith(prefix));
        return before - meta.size();
    }
}
