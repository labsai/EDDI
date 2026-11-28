/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.hitl.oversight.OversightErrors;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Who may see and do what on the oversight API.
 * <ul>
 * <li><b>Read</b> (analytics, samples, autonomy state) and <b>review a
 * sample</b>: administrators and {@code eddi-approver}s for any agent
 * (approvers already see every pending approval); anyone else must name an
 * agent they hold {@code EDIT} on.</li>
 * <li><b>Change autonomy</b> (apply, dismiss, lift): administrators, or an
 * {@code eddi-editor} with {@code EDIT} on the agent — the same right that
 * edits its configuration. Never {@code eddi-approver} alone, workspaces on or
 * off. Suspending is allowed to reviewers too: it only adds a gate.</li>
 * </ul>
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class OversightAccess {

    static final String APPROVER_ROLE = "eddi-approver";
    static final String EDITOR_ROLE = "eddi-editor";
    static final String ANONYMOUS = "anonymous";

    private final ResourceAccessGuard accessGuard;
    private final ConversationAccessGuard conversationAccessGuard;
    private final SecurityIdentity identity;

    @Inject
    public OversightAccess(ResourceAccessGuard accessGuard, ConversationAccessGuard conversationAccessGuard, SecurityIdentity identity) {
        this.accessGuard = accessGuard;
        this.conversationAccessGuard = conversationAccessGuard;
        this.identity = identity;
    }

    /** Whether the caller sees every agent's oversight data. */
    public boolean seesAll() {
        return accessGuard.seesEverything() || isApprover();
    }

    /** Whether the caller may see user ids. */
    public boolean isAdmin() {
        return accessGuard.isAdmin();
    }

    /**
     * The agent a read is scoped to: the named one after an access check, or null
     * (all agents) for a caller who sees everything.
     */
    public String requireReadScope(String agentId) {
        String id = blankToNull(agentId);
        if (id == null) {
            if (!seesAll()) {
                throw OversightErrors.badRequest(OversightErrors.AGENT_ID_REQUIRED, "agentId is required");
            }
            return null;
        }
        requireReviewer(id);
        return id;
    }

    /** May read and review this agent's samples. */
    public void requireReviewer(String agentId) {
        if (seesAll()) {
            return;
        }
        requireEditor(agentId);
    }

    /**
     * May change this agent's earned autonomy: an administrator, or an
     * {@code eddi-editor} who (under workspace enforcement) holds {@code EDIT} on
     * the agent.
     * <p>
     * The role is checked <em>before</em> the {@code seesEverything()} shortcut.
     * That shortcut is true for every caller while workspaces are not enforced —
     * the default — and the resource also admits {@code eddi-approver}, so without
     * the role check an approver could lift a suspension or apply a recommendation
     * on any agent, i.e. remove gates it is only meant to decide.
     */
    public void requireEditor(String agentId) {
        if (!accessGuard.isAdmin() && !hasRole(EDITOR_ROLE)) {
            throw OversightErrors.forbidden(OversightErrors.NOT_PERMITTED, "Access denied");
        }
        if (accessGuard.seesEverything()) {
            return;
        }
        if (agentId == null) {
            throw OversightErrors.forbidden(OversightErrors.NOT_PERMITTED, "Access denied");
        }
        accessGuard.requireAccess(agentId, AccessLevel.EDIT, "agent");
    }

    /** The caller, as recorded in reviews and the ledger. */
    public String actor() {
        return conversationAccessGuard.callerActor(ANONYMOUS);
    }

    private boolean isApprover() {
        return hasRole(APPROVER_ROLE);
    }

    private boolean hasRole(String role) {
        return identity != null && !identity.isAnonymous() && identity.hasRole(role);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
