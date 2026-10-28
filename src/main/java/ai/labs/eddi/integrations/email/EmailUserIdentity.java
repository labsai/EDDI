/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import java.util.Optional;
import java.util.UUID;

/**
 * How a mail's sender is named inside EDDI (email plan §9).
 * <ul>
 * <li>{@code email:<integrationId>:<normalised address>} — an authenticated
 * sender ({@code ALIGNED_PASS}) under {@code identityMode: perSender}. The
 * prefix keeps it out of every other identity source's namespace, and the
 * integration id keeps a weakly configured integration from reading memories
 * built under a strict one.</li>
 * <li>{@code email:<integrationId>} — every authenticated sender under
 * {@code identityMode: integration}: one shared identity, like a webhook.</li>
 * <li>{@code email-anon:<random>} — a sender that is NOT authenticated, on an
 * integration that runs such mail at all ({@code requireAuthentication:
 * false}). One-off, so it never correlates with anything, and it gets no
 * long-term memory: {@code ReservedPrincipals#hasNoPersistentMemory}.</li>
 * </ul>
 * All of them are {@code SELF_ASSERTED}: nothing an inbound mail proves makes a
 * principal {@code VERIFIED}, so {@code PER_USER} connections are refused.
 */
public final class EmailUserIdentity {

    /** Prefix of every user id an authenticated email turn runs as. */
    public static final String PREFIX = "email:";

    /** Prefix of the one-off user id of an unauthenticated sender. */
    public static final String ANONYMOUS_PREFIX = "email-anon:";

    private EmailUserIdentity() {
    }

    /** {@code email:<integrationId>:<address>}. */
    public static String perSender(String integrationId, String normalizedAddress) {
        return PREFIX + integrationId + ":" + normalizedAddress;
    }

    /** {@code email:<integrationId>}. */
    public static String integration(String integrationId) {
        return PREFIX + integrationId;
    }

    /** A fresh {@code email-anon:<random>}. */
    public static String anonymous() {
        return ANONYMOUS_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * The address of a per-sender email user id, or empty for any other id
     * (including the shared integration identity).
     */
    public static Optional<String> addressOf(String userId) {
        if (userId == null || !userId.startsWith(PREFIX)) {
            return Optional.empty();
        }
        String rest = userId.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0 || colon == rest.length() - 1) {
            return Optional.empty();
        }
        String address = rest.substring(colon + 1);
        return address.indexOf('@') > 0 ? Optional.of(address) : Optional.empty();
    }

    /** Whether {@code userId} is any email identity, anonymous ones included. */
    public static boolean isEmailIdentity(String userId) {
        return userId != null && (userId.startsWith(PREFIX) || userId.startsWith(ANONYMOUS_PREFIX));
    }
}
