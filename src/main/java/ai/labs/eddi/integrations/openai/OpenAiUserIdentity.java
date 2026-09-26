/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

/**
 * How an Open WebUI (OpenAI-compatible {@code /v1}) user is named inside EDDI.
 * <p>
 * The {@code X-OpenWebUI-User-Id} header used to become the EDDI {@code userId}
 * verbatim. That id lives in the same namespace as OIDC principals and every
 * other identity source, so a shared-key caller who set the header to an OIDC
 * user's principal reached that user's conversations and long-term memories.
 * The header is attacker-chosen once the shared key leaks, which is exactly the
 * reach this closes.
 * <p>
 * A header-derived id is now {@code openwebui:<id>}: prefixed so it can never
 * equal a bare OIDC principal (or a raw Slack id, or any other source). This
 * does <b>not</b> change the documented {@code /v1} trust model — a leaked
 * shared key still lets the holder impersonate any <em>Open WebUI</em> user by
 * setting the header — it only stops that header from reaching identities in
 * other namespaces.
 * <p>
 * OIDC principals (in {@code authenticated} mode) and the configured anonymous
 * default are NOT namespaced here: the first is already a real, verified
 * identity and the second is operator config, not caller-controlled.
 * <p>
 * <b>Compatibility with data stored under the raw header id.</b> Handled in
 * {@link OpenAiConversationBridge} and it is <b>adopt-only</b>: a chat whose
 * conversation mapping was stored under the raw id is re-keyed to the
 * namespaced id, and because that conversation keeps its raw-id owner its
 * long-term memories load with no move. There is deliberately <b>no</b>
 * standalone move of memory out of the bare-id namespace — that namespace is
 * shared with OIDC principals and the raw id is caller-supplied, so a move
 * could relocate and erase another user's memories (review Finding A). A
 * brand-new chat therefore does not inherit memories the raw id accumulated
 * before namespacing.
 */
public final class OpenAiUserIdentity {

    /** Prefix of every EDDI user id derived from an Open WebUI header. */
    public static final String PREFIX = "openwebui:";

    private OpenAiUserIdentity() {
    }

    /**
     * The EDDI user id for a raw {@code X-OpenWebUI-User-Id} value. Idempotent: a
     * value that already carries the prefix is returned unchanged, so
     * re-namespacing cannot double it.
     */
    public static String namespace(String rawHeaderId) {
        if (rawHeaderId == null) {
            return null;
        }
        return isNamespaced(rawHeaderId) ? rawHeaderId : PREFIX + rawHeaderId;
    }

    /** Whether {@code userId} is an Open WebUI-derived (namespaced) id. */
    public static boolean isNamespaced(String userId) {
        return userId != null && userId.startsWith(PREFIX);
    }

    /**
     * The raw header id a namespaced id was derived from — the id data may still be
     * stored under from before namespacing. {@code null} for a non-namespaced id
     * (OIDC principal, anonymous default), which is never migrated.
     */
    public static String rawId(String namespacedUserId) {
        return isNamespaced(namespacedUserId) ? namespacedUserId.substring(PREFIX.length()) : null;
    }
}
