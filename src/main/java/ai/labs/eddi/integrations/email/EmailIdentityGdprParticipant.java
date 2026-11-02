/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.engine.gdpr.GdprComplianceService;
import ai.labs.eddi.engine.gdpr.GdprDeletionResult;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import ai.labs.eddi.integrations.email.EmailIntegrationDirectory.EmailIntegration;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger;
import ai.labs.eddi.integrations.email.ledger.IEmailMessageLedger.LedgerEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Erasure and export of what the email channel holds about a mail address
 * (email plan §17).
 * <p>
 * An email correspondent's conversations, memories and properties are stored
 * under {@code email:<integrationId>:<address>} — one user id per integration
 * they wrote to. An erasure request for the <em>address</em> (the person knows
 * their address, not EDDI's ids) therefore runs the full erasure cascade once
 * for every such id: the email integrations that exist now, plus every
 * integration an inbound ledger row of that address names (covering
 * integrations deleted since, within the ledger's retention). The inbound
 * ledger rows themselves (the address is their {@code recipient}) are erased by
 * {@code EmailLedgerGdprParticipant}.
 * <p>
 * Nothing is expanded for an id that already is an email identity, so the
 * nested cascades do not recurse. The one-off {@code email-anon:} ids of
 * unauthenticated senders hold no memory and are not linked to an address at
 * all; their conversations are reached by conversation id, like any anonymous
 * conversation.
 */
@ApplicationScoped
public class EmailIdentityGdprParticipant implements IGdprParticipant {

    private final Instance<GdprComplianceService> gdpr;
    private final EmailIntegrationDirectory directory;
    private final IEmailMessageLedger ledger;

    @Inject
    public EmailIdentityGdprParticipant(Instance<GdprComplianceService> gdpr, EmailIntegrationDirectory directory, IEmailMessageLedger ledger) {
        this.gdpr = gdpr;
        this.directory = directory;
        this.ledger = ledger;
    }

    @Override
    public String name() {
        return "emailIdentities";
    }

    @Override
    public long erase(String userId) {
        long removed = 0;
        int incomplete = 0;
        for (String emailUserId : emailUserIdsOf(userId)) {
            GdprDeletionResult result = gdpr.get().deleteUserData(emailUserId);
            removed += result == null ? 0 : total(result);
            if (result != null && result.failedSteps() != null && !result.failedSteps().isEmpty()) {
                incomplete++;
            }
        }
        if (incomplete > 0) {
            // Surfaces as this step's failure in the outer result, so the erasure is not
            // reported complete while an email identity's data is still there.
            throw new IllegalStateException("The erasure of " + incomplete + " email identities of this address was incomplete");
        }
        return removed;
    }

    @Override
    public Object export(String userId) {
        Map<String, Object> exports = new LinkedHashMap<>();
        for (String emailUserId : emailUserIdsOf(userId)) {
            exports.put(emailUserId, gdpr.get().exportUserData(emailUserId));
        }
        return exports.isEmpty() ? null : exports;
    }

    /**
     * The email user ids of the address {@code userId} names; empty when it is not
     * an address, or is itself an email identity.
     */
    Set<String> emailUserIdsOf(String userId) {
        Set<String> ids = new LinkedHashSet<>();
        if (userId == null || EmailUserIdentity.isEmailIdentity(userId) || gdpr == null || !gdpr.isResolvable()) {
            return ids;
        }
        Optional<String> address = AddressNormalizer.tryNormalize(userId);
        if (address.isEmpty()) {
            return ids;
        }
        Set<String> integrations = new LinkedHashSet<>();
        for (EmailIntegration integration : directory.scan()) {
            integrations.add(integration.id());
        }
        for (LedgerEntry row : ledger.findByRecipient(address.get())) {
            if (row.direction() == IEmailMessageLedger.Direction.IN) {
                integrations.add(row.integrationId());
            }
        }
        for (String integrationId : integrations) {
            ids.add(EmailUserIdentity.perSender(integrationId, address.get()));
        }
        return ids;
    }

    private static long total(GdprDeletionResult result) {
        return result.memoriesDeleted() + result.conversationsDeleted();
    }

    @Override
    public int order() {
        // Before the email ledger participant (0) reads the rows that name the
        // integrations, and before the user directory (LAST).
        return -10;
    }
}
