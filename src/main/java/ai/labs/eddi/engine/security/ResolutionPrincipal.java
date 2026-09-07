/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

/**
 * Whose authority a per-user credential is resolved under for the turn
 * currently executing — and how much that identity is actually worth.
 * <p>
 * A bare user id does not say who asserted it.
 * {@code authorization.enabled=true} was being read as proof that the
 * conversation's user id had been verified, and it is not: the
 * OpenAI-compatible {@code /v1} adapter, in api-key mode with
 * {@code eddi.openai-compat.trust-user-headers=true} (the shipped default),
 * believes a caller-supplied {@code X-OpenWebUI-User-Id} verbatim once the
 * shared key matches. A holder of that one key can therefore open a
 * conversation as any user, and anything that trusts the conversation's user id
 * will hand them that user's live SaaS tokens. Carrying the provenance next to
 * the id is what lets {@code ConnectionResolver} tell those two populations
 * apart at the moment it releases a credential.
 * <p>
 * Distinct from {@link CallerIdentity}, which describes whoever is driving the
 * current HTTP request. The two differ on a HITL resume — the request belongs
 * to the approver, the conversation to the user who asked — which is exactly
 * the case a credential decision must not read from the thread's request
 * identity.
 *
 * @param userId
 *            the conversation's user id, or {@code null} when the turn has none
 * @param provenance
 *            how that id came to be; {@code null} counts as not verified
 */
public record ResolutionPrincipal(String userId, Provenance provenance) {

    /**
     * How much an identity is worth for a credential decision.
     * <p>
     * Two values, because the only question that matters is whether something
     * authenticated this user id. A finer taxonomy — which surface, which proxy —
     * belongs in the connection's own configuration, where an operator can say that
     * they trust it, rather than in a value the engine derives.
     */
    public enum Provenance {

        /**
         * An authenticated identity produced this user id: it matched the principal of
         * the request that created the conversation.
         */
        VERIFIED,

        /**
         * Nobody authenticated this user id. It was self-asserted by the caller, taken
         * from a trusted-proxy header, generated for an anonymous session, or the
         * conversation predates provenance being recorded at all.
         */
        SELF_ASSERTED
    }

    /**
     * Whether this principal may be handed a {@code PER_USER} credential without an
     * explicit per-connection opt-in.
     */
    public boolean isVerified() {
        return provenance == Provenance.VERIFIED && hasUserId();
    }

    /** Whether there is a user id to resolve a grant under at all. */
    public boolean hasUserId() {
        return userId != null && !userId.isBlank();
    }
}
