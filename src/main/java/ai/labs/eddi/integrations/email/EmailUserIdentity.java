/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /**
     * A fresh {@code email-consult:<integrationId>:<random>}: what an agent an
     * email turn consults runs as (email v2 plan §10.3). One-off and memory-less
     * ({@code ReservedPrincipals#hasNoPersistentMemory}), so the consulted agent
     * neither reads nor writes the sender's long-term memory — {@code global}
     * entries are visible across agents, so scoping by agent would not do — and
     * owns nothing the sender owns.
     */
    public static String consult(String integrationId) {
        return CONSULT_PREFIX + (integrationId == null || integrationId.isBlank() ? "unknown" : integrationId) + ":"
                + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * {@code email-consult:<integrationId>:<hash>}: what an agent runs as when a
     * mail from {@code normalizedAddress} consults it. Memory-less like
     * {@link #consult(String)}, but the same for every consultation of that sender
     * on that integration, so an erasure or export request for the address finds
     * the consulted conversations ({@code EmailIdentityGdprParticipant}). The hash
     * names nobody to the consulted agent.
     */
    public static String consult(String integrationId, String normalizedAddress) {
        String id = integrationId == null || integrationId.isBlank() ? "unknown" : integrationId;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((id + "\n" + normalizedAddress).getBytes(StandardCharsets.UTF_8));
            return CONSULT_PREFIX + id + ":" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Whether {@code userId} is a consulted agent's identity. */
    public static boolean isConsultIdentity(String userId) {
        return userId != null && userId.startsWith(CONSULT_PREFIX);
    }

    /**
     * Whether a turn of {@code userId} came from a mail: a sender's identity of any
     * kind, or a consulted agent's.
     */
    public static boolean originatesFromEmail(String userId) {
        return isEmailIdentity(userId) || isConsultIdentity(userId) || (userId != null && userId.startsWith(THREAD_PREFIX));
    }

    /**
     * Prefix of the thread identity email v2 Phase 5 introduces
     * ({@code email-thread:<integrationId>:<hash>}); reserved here already so no
     * signed-in user can carry it and a turn of it counts as email-originated.
     */
    public static final String THREAD_PREFIX = "email-thread:";

    /**
     * The integration id of {@code email:<integrationId>[:<address>]} or
     * {@code email-consult:<integrationId>:<random>}; empty for an anonymous sender
     * (whose id names no integration) and every other id.
     */
    public static Optional<String> integrationIdOf(String userId) {
        if (userId == null) {
            return Optional.empty();
        }
        String rest;
        if (userId.startsWith(PREFIX)) {
            rest = userId.substring(PREFIX.length());
        } else if (userId.startsWith(CONSULT_PREFIX)) {
            rest = userId.substring(CONSULT_PREFIX.length());
        } else {
            return Optional.empty();
        }
        int colon = rest.indexOf(':');
        String id = colon < 0 ? rest : rest.substring(0, colon);
        return id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    /** Prefix of the one-off user id a consulted agent runs as. */
    public static final String CONSULT_PREFIX = "email-consult:";
}
