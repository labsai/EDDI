/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link IEmailSuppressionStore} in memory, for unit tests; the real stores are
 * held to the same contract by {@link EmailSuppressionStoreContract}.
 */
public class InMemoryEmailSuppressionStore implements IEmailSuppressionStore {

    private final Map<String, Suppression> entries = new LinkedHashMap<>();

    private static String key(String integrationId, String hash) {
        return integrationId + "|" + hash;
    }

    @Override
    public synchronized boolean suppress(String integrationId, String normalizedAddress, String reason, Instant now) {
        String hash = IEmailSuppressionStore.hash(normalizedAddress);
        return entries.putIfAbsent(key(integrationId, hash),
                new Suppression(integrationId, hash, IEmailSuppressionStore.domainOf(normalizedAddress), reason, now)) == null;
    }

    @Override
    public synchronized boolean isSuppressed(String integrationId, String normalizedAddress) {
        return entries.containsKey(key(integrationId, IEmailSuppressionStore.hash(normalizedAddress)));
    }

    @Override
    public synchronized List<Suppression> list(String integrationId, int limit) {
        return entries.values().stream().filter(s -> s.integrationId().equals(integrationId))
                .sorted(Comparator.comparing(Suppression::createdAt).reversed()).limit(limit).toList();
    }

    @Override
    public synchronized boolean remove(String integrationId, String normalizedAddress) {
        return entries.remove(key(integrationId, IEmailSuppressionStore.hash(normalizedAddress))) != null;
    }

    @Override
    public synchronized long removeAll(String integrationId) {
        int before = entries.size();
        entries.values().removeIf(s -> s.integrationId().equals(integrationId));
        return before - entries.size();
    }

    @Override
    public synchronized List<Suppression> findByAddress(String normalizedAddress) {
        String hash = IEmailSuppressionStore.hash(normalizedAddress);
        return entries.values().stream().filter(s -> s.addressHash().equals(hash)).toList();
    }

    @Override
    public synchronized long deleteByAddress(String normalizedAddress) {
        String hash = IEmailSuppressionStore.hash(normalizedAddress);
        int before = entries.size();
        entries.values().removeIf(s -> s.addressHash().equals(hash));
        return before - entries.size();
    }
}
