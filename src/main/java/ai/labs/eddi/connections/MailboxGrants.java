/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.engine.mail.AddressNormalizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The grants of {@link Binding#MAILBOX} connections: one per dedicated mailbox,
 * filed in the ordinary grant store under the principal
 * {@code mailbox:<normalised address>}.
 * <p>
 * The prefix is a reserved principal namespace
 * ({@code ReservedPrincipals.PREFIXES}): no signed-in user, token claim or
 * email sender identity may carry it, so no per-user flow can ever list,
 * overwrite or resolve a mailbox grant, and a mailbox grant can never be
 * mistaken for a person's.
 */
@ApplicationScoped
public class MailboxGrants {

    /** Principal prefix of a mailbox grant. */
    public static final String PRINCIPAL_PREFIX = "mailbox:";

    private final IConnectionGrantStore grantStore;

    @Inject
    public MailboxGrants(IConnectionGrantStore grantStore) {
        this.grantStore = grantStore;
    }

    /**
     * The grant principal of {@code address}.
     *
     * @throws IllegalArgumentException
     *             when {@code address} is not a plain email address (the message
     *             never echoes it)
     */
    public static String principalOf(String address) {
        return PRINCIPAL_PREFIX + AddressNormalizer.normalize(address);
    }

    /**
     * The mailbox address a grant principal names, if it is a mailbox principal.
     */
    public static Optional<String> addressOf(String principal) {
        if (principal == null || !principal.startsWith(PRINCIPAL_PREFIX)) {
            return Optional.empty();
        }
        return AddressNormalizer.tryNormalize(principal.substring(PRINCIPAL_PREFIX.length()));
    }

    /**
     * The grant of one mailbox on one connection, if linked. A malformed address is
     * simply not linked.
     */
    public Optional<ConnectionGrant> find(String connectionName, String address) {
        Optional<String> normalized = AddressNormalizer.tryNormalize(address);
        if (normalized.isEmpty() || connectionName == null) {
            return Optional.empty();
        }
        return grantStore.find(ConnectionReference.DEFAULT_TENANT, connectionName, PRINCIPAL_PREFIX + normalized.get());
    }

    /**
     * Whether a usable grant exists for the mailbox: {@code ACTIVE}, or
     * {@code EXPIRED} (renewed by its refresh token on the next poll). A
     * {@code REFRESH_FAILED} or {@code REVOKED} grant must be linked again.
     */
    public boolean isLinked(String connectionName, String address) {
        return find(connectionName, address).map(MailboxGrants::isUsable).orElse(false);
    }

    /** Whether a grant can still produce a token without being linked again. */
    public static boolean isUsable(ConnectionGrant grant) {
        return grant.getStatus() == ConnectionGrant.Status.ACTIVE || grant.getStatus() == ConnectionGrant.Status.EXPIRED;
    }

    /**
     * Every mailbox grant of one connection, by address. Filtered from the tenant's
     * grants rather than read by a store query of its own: a connection holds a
     * handful of mailboxes, and a second query in both store twins would be one
     * more thing to keep in step.
     */
    public List<ConnectionGrant> list(String connectionName) {
        return grantStore.findByTenant(ConnectionReference.DEFAULT_TENANT).stream()
                .filter(grant -> connectionName.equals(grant.getConnectionName()) && addressOf(grant.getPrincipal()).isPresent())
                .sorted(Comparator.comparing(ConnectionGrant::getPrincipal)).toList();
    }

    /**
     * Unlinks one mailbox.
     *
     * @return whether a grant existed
     */
    public boolean delete(String connectionName, String address) {
        return grantStore.delete(ConnectionReference.DEFAULT_TENANT, connectionName, principalOf(address));
    }
}
