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
import java.util.regex.Pattern;

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
 * <li>{@code email-thread:<integrationId>:<sha256(threadRoot)>} — every
 * conversation of an integration whose replies copy the thread
 * ({@code replyAudience: thread}, email v2 plan §9.2). More people read the
 * answer than the sender, so the turn must not load the sender's long-term
 * memory: this identity has none
 * ({@code ReservedPrincipals#hasNoPersistentMemory}). A sender who is not a
 * participant of the thread's conversation gets
 * {@code email-thread:<integrationId>:<sha256(threadRoot + "\n" + sender)>}, a
 * conversation of their own.</li>
 * </ul>
 * All of them are {@code SELF_ASSERTED}: nothing an inbound mail proves makes a
 * principal {@code VERIFIED}, so {@code PER_USER} connections are refused.
 */
public final class EmailUserIdentity {

    /** Prefix of every user id an authenticated email turn runs as. */
    public static final String PREFIX = "email:";

    /** Prefix of the one-off user id of an unauthenticated sender. */
    public static final String ANONYMOUS_PREFIX = "email-anon:";

    /**
     * Prefix of the memory-less user id of a conversation whose replies copy the
     * thread.
     */
    public static final String THREAD_PREFIX = "email-thread:";

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

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

    /** {@code email-thread:<integrationId>:<sha256(threadRoot)>}. */
    public static String thread(String integrationId, String threadRoot) {
        return THREAD_PREFIX + integrationId + ":" + sha256(threadRoot);
    }

    /**
     * {@code email-thread:<integrationId>:<sha256(threadRoot + "\n" + sender)>}:
     * the thread identity of a sender who may not continue the thread's shared
     * conversation.
     */
    public static String threadForSender(String integrationId, String threadRoot, String normalizedSender) {
        return THREAD_PREFIX + integrationId + ":" + sha256(threadRoot + "\n" + normalizedSender);
    }

    /**
     * Whether {@code userId} is a thread identity of {@code integrationId} — never
     * another integration's, whose id merely starts the same way.
     */
    public static boolean isThreadIdentityOf(String userId, String integrationId) {
        if (userId == null || integrationId == null || integrationId.isEmpty()) {
            return false;
        }
        String prefix = THREAD_PREFIX + integrationId + ":";
        return userId.startsWith(prefix) && DIGEST.matcher(userId.substring(prefix.length())).matches();
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
        return userId != null && (userId.startsWith(PREFIX) || userId.startsWith(ANONYMOUS_PREFIX) || userId.startsWith(THREAD_PREFIX));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
