/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** {@link IEmailMailboxStateStore} in memory, for unit tests. */
public class InMemoryEmailMailboxStateStore implements IEmailMailboxStateStore {

    private final Map<String, MailboxState> states = new HashMap<>();
    private final Set<String> notices = new HashSet<>();

    @Override
    public synchronized Optional<MailboxState> read(String integrationId) {
        return Optional.ofNullable(states.get(integrationId));
    }

    private MailboxState current(String id) {
        return states.getOrDefault(id, new MailboxState(id, null, 0, 0, null, 0, null, null, null, 0.0, null));
    }

    @Override
    public synchronized void saveCursor(String integrationId, String folder, long uidValidity, long lastUid, Instant since, Instant now) {
        MailboxState s = current(integrationId);
        states.put(integrationId,
                new MailboxState(integrationId, folder, uidValidity, lastUid, since, 0, null, null, s.budgetDay(), s.spentUsd(), now));
    }

    @Override
    public synchronized void recordFailure(String integrationId, int failures, Instant backoffUntil, String lastError, Instant now) {
        MailboxState s = current(integrationId);
        states.put(integrationId, new MailboxState(integrationId, s.folder(), s.uidValidity(), s.lastUid(), s.since(), failures, backoffUntil,
                lastError, s.budgetDay(), s.spentUsd(), now));
    }

    @Override
    public synchronized double addSpend(String integrationId, String day, double amountUsd, Instant now) {
        MailboxState s = current(integrationId);
        double spent = day.equals(s.budgetDay()) ? s.spentUsd() + amountUsd : amountUsd;
        states.put(integrationId, new MailboxState(integrationId, s.folder(), s.uidValidity(), s.lastUid(), s.since(), s.failures(), s.backoffUntil(),
                s.lastError(), day, spent, now));
        return spent;
    }

    @Override
    public synchronized boolean claimNotice(String integrationId, String noticeKey, Instant now) {
        return notices.add(integrationId + "|" + noticeKey);
    }

    @Override
    public synchronized void delete(String integrationId) {
        states.remove(integrationId);
        notices.removeIf(key -> key.startsWith(integrationId + "|"));
    }
}
