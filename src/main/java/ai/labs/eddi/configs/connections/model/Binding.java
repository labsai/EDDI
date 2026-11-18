/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.model;

/**
 * Whose credential a connection resolves.
 * <p>
 * This one field is what makes "an org-wide Amplitude key" and "each end user's
 * own Google Drive" the same system rather than two features. It is a per-
 * connection config choice, never a global mode.
 */
public enum Binding {

    /**
     * One grant, shared by every user of every agent that references the
     * connection. The right answer for analytics keys, service accounts and
     * anything where the agent acts as itself rather than as a person.
     */
    SERVICE,

    /**
     * The calling user's own grant. Resolution fails closed when there is no
     * <em>verified</em> principal — a scheduled turn, a trigger, or a caller whose
     * identity is only self-asserted — rather than falling back to the service
     * grant, because sending the wrong authority is worse than sending none.
     */
    PER_USER,

    /**
     * A credential the caller hands to EDDI on the request itself, for a platform
     * that has already authenticated the user and passes their credential inward.
     * EDDI stores nothing: the value rides {@code CallerIdentity} for the turn and
     * is never written to the conversation store, an export, or the debugger.
     * <p>
     * The reason to choose this over {@link #SERVICE} is authority, not
     * convenience. An agent holding one shared key can reach everything that key
     * can, and only the agent's own reasoning stands between a user and data they
     * should not see. A caller-supplied credential makes the target platform's
     * authorization the boundary — the agent cannot do what the user cannot do —
     * without EDDI modelling that platform's permissions at all.
     * <p>
     * Distinct from {@link #PER_USER}, which also acts as the user but holds the
     * grant: that one needs an OAuth consent screen and a stored refresh token,
     * neither of which exists when the credential simply arrives with the request.
     * Distinct too from {@code ${caller:token}}, which relays EDDI's <em>own</em>
     * credential and only ever back to the origin the caller addressed; this one
     * carries a credential for a different system entirely, to wherever the
     * connection's {@code baseUrlAllowlist} permits.
     */
    CALLER_SUPPLIED,

    /**
     * One grant per <em>dedicated mailbox</em>, linked by an administrator, used
     * only by the email channel's mail transport (IMAP and SMTP {@code XOAUTH2}).
     * <p>
     * Legal only with {@code OAUTH2_AUTHORIZATION_CODE}: a Google Workspace user
     * mailbox can only be reached with the authorization-code flow (domain-wide
     * delegation is deliberately out), so somebody has to sign in as the mailbox
     * once. That somebody is an {@code eddi-admin} acting for the mailbox, not a
     * person acting for themselves, which is why it is not {@link #PER_USER}: the
     * grant is filed under the principal {@code mailbox:<address>}, a reserved
     * namespace no user can carry, and the connection's {@code oauth.accountCheck}
     * proves at link time and on every refresh that the provider account really is
     * that address.
     * <p>
     * A mailbox grant reads and sends the mailbox's whole mail, so every resolver
     * entry point other than the mail transport's refuses it
     * ({@code UNSUPPORTED_PLACEMENT}): httpcalls, MCP, A2A, ingestion and the
     * system mailer can never spend it.
     */
    MAILBOX
}
