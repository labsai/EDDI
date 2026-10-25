/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.audit.AuditHmac;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.integrations.openai.OpenAiUserIdentity;
import ai.labs.eddi.integrations.slack.SlackUserIdentity;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The principal names EDDI makes up for itself, which no signed-in user may
 * carry.
 * <p>
 * Ownership is a string comparison: a conversation, a memory entry or a HITL
 * decision belongs to whoever's principal equals its {@code userId}. EDDI
 * writes synthetic principals into those fields for callers that have no token
 * of their own — a Slack user ({@code slack:<team>:<user>}), an Open WebUI user
 * named in a header ({@code openwebui:…}), a group's shared memory
 * ({@code group:<id>}), an A2A peer, the scheduler and other engine work
 * ({@code system:…}). An identity provider that lets {@code :} into a username
 * would let a person sign in as {@code slack:T1:U2} and own that Slack user's
 * conversations, or as {@code group:<id>} and read a team's RETRO memory.
 * {@code NamelessPrincipalAugmentor} refuses such a token at authentication.
 * <p>
 * This is the one list. {@code ReservedPrincipalsTest} scans {@code src/main}
 * for string prefixes that look like a principal namespace, and fails until a
 * new one is either added here or recorded there as not being a principal.
 */
public final class ReservedPrincipals {

    /**
     * What a resume's {@code decidedBy} is prefixed with when it is the caller's
     * own claim and nobody is authenticated: a claim, not an identity.
     */
    public static final String UNVERIFIED_APPROVER_PREFIX = "unverified:";

    /**
     * Prefixes of synthetic principals. Compared case-insensitively, so a
     * {@code Slack:} spelling is refused too.
     */
    public static final List<String> PREFIXES = List.of(
            // Open WebUI users named by the trusted X-OpenWebUI-User-Id header (/v1)
            OpenAiUserIdentity.PREFIX,
            // Slack users of a channel integration: slack:<team>:<user>
            SlackUserIdentity.PREFIX,
            // Signed inbound webhook channel events (PR #1059): webhook:<integrationId>
            "webhook:",
            // Email channel senders: email:<integrationId>[:<address>]
            EmailUserIdentity.PREFIX,
            // Unauthenticated email senders, one-off and memory-less: email-anon:<random>
            EmailUserIdentity.ANONYMOUS_PREFIX,
            // Agent test-harness conversations (PR #1065), which switch tool mocking on
            "agent-test:",
            // A group's shared (team) memory owner: group:<groupId>
            IUserMemoryStore.TEAM_OWNER_PREFIX,
            // Engine actors: system:scheduler, system:timeout, system:retention, …
            "system:",
            // A2A peers: a2a:<principal>
            "a2a:",
            // HITL decisions taken over MCP: mcp:<name>
            "mcp:",
            // An approver named only by the caller's own claim (no one authenticated):
            // unverified:<claim>. Recorded as decidedBy, so a token may not look like one.
            UNVERIFIED_APPROVER_PREFIX,
            // Owner stamped on a conversation started without any identity
            "anonymous-",
            // The pseudonym an erased user's records are rewritten to
            AuditHmac.GDPR_PSEUDONYM_PREFIX);

    /**
     * Whole names that are synthetic too: the owner recorded for work done while
     * authorization was off.
     */
    public static final Set<String> NAMES = Set.of("anonymous");

    private ReservedPrincipals() {
    }

    /**
     * The reserved prefix (or name) {@code principal} carries, or {@code null} when
     * it carries none.
     */
    public static String reservedPartOf(String principal) {
        if (principal == null) {
            return null;
        }
        String lowered = principal.strip().toLowerCase(Locale.ROOT);
        if (NAMES.contains(lowered)) {
            return lowered;
        }
        for (String prefix : PREFIXES) {
            if (lowered.startsWith(prefix)) {
                return prefix;
            }
        }
        return null;
    }

    /**
     * Whether conversations of {@code userId} must neither read nor write long-term
     * user memory: the one-off identity of an unauthenticated email sender. Its
     * {@code From} is a claim anyone can make, so whatever such a turn remembered
     * would be readable by the next forger — and nothing it could recall belongs to
     * it.
     */
    public static boolean hasNoPersistentMemory(String userId) {
        return userId != null && userId.startsWith(EmailUserIdentity.ANONYMOUS_PREFIX);
    }

    /** Whether {@code principal} is in a namespace EDDI reserves for itself. */
    public static boolean isReserved(String principal) {
        return reservedPartOf(principal) != null;
    }
}
