/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.integrations.email.ledger.IEmailSuppressionStore.Suppression;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Erases and exports the email suppression entries of a person (email plan §17,
 * open question §23 Q4).
 * <p>
 * <b>Erasure deletes the entry</b> rather than keeping a hash to honour "never
 * mail me again": conversation mode only ever answers mail the person sent, so
 * after erasure EDDI writes to them again only if they write first — and if
 * that reply bounces, the entry comes back. Keeping a hash of an erased
 * person's address would keep exactly the identifier the request asked to
 * remove.
 * <p>
 * The addresses that are the person's are resolved as for the ledger
 * ({@link EmailLedgerGdprParticipant#addressesOf}).
 */
@ApplicationScoped
public class EmailSuppressionGdprParticipant implements IGdprParticipant {

    private final IEmailSuppressionStore store;
    private final EmailLedgerGdprParticipant addresses;

    @Inject
    public EmailSuppressionGdprParticipant(IEmailSuppressionStore store, EmailLedgerGdprParticipant addresses) {
        this.store = store;
        this.addresses = addresses;
    }

    @Override
    public String name() {
        return "emailSuppressions";
    }

    @Override
    public long erase(String userId) {
        long removed = 0;
        for (String address : addresses.addressesOf(userId)) {
            removed += store.deleteByAddress(address);
        }
        return removed;
    }

    @Override
    public Object export(String userId) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String address : addresses.addressesOf(userId)) {
            for (Suppression suppression : store.findByAddress(address)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("address", address);
                entry.put("integrationId", suppression.integrationId());
                entry.put("reason", suppression.reason());
                entry.put("createdAt", suppression.createdAt() == null ? null : suppression.createdAt().toString());
                entries.add(entry);
            }
        }
        return entries.isEmpty() ? null : entries;
    }

    @Override
    public int order() {
        // Before the user directory, whose email address this participant reads.
        return 0;
    }
}
