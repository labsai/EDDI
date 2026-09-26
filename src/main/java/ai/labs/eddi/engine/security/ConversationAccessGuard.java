/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ContextNotActiveException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Single source of truth for <em>who may access a (1:1) conversation</em>,
 * shared by the REST layer ({@code RestAgentEngine}) and the MCP layer
 * ({@code McpConversationTools}).
 * <p>
 * It is the non-HITL sibling of
 * {@link ai.labs.eddi.engine.hitl.HitlAccessGuard}: same composition (delegate
 * role/identity decisions to {@link OwnershipValidator}, resolve the owner
 * through the conversation descriptor), but plain owner-or-admin semantics —
 * the {@code eddi-approver} role grants nothing here, because reading or
 * driving a conversation is not deciding a pending approval.
 * <p>
 * Extracting the check keeps "who may read this conversation" from drifting
 * between the surfaces: a role check alone (e.g. {@code eddi-viewer}) is a
 * coarse gate that says nothing about <em>whose</em> conversation is being
 * read.
 *
 * @author ginccc
 */
@ApplicationScoped
public class ConversationAccessGuard {

    private static final Logger LOGGER = Logger.getLogger(ConversationAccessGuard.class);
    private static final String RESOURCE_TYPE = "conversation";

    /**
     * Conversation descriptors are written once and updated in place, so they never
     * leave version 0 — the archived copy a soft delete leaves keeps it too.
     */
    private static final int DESCRIPTOR_VERSION = 0;

    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final IConversationDescriptorStore conversationDescriptorStore;

    @Inject
    public ConversationAccessGuard(SecurityIdentity identity,
            OwnershipValidator ownershipValidator,
            IConversationDescriptorStore conversationDescriptorStore) {
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.conversationDescriptorStore = conversationDescriptorStore;
    }

    /**
     * Asserts that the caller owns the conversation, or holds {@code eddi-admin}. A
     * conversation whose descriptor records no owner (legacy data) is left
     * accessible — {@link OwnershipValidator#requireOwnerOrAdmin} decides that.
     * <p>
     * The owner is resolved from the live descriptor, and — when that is gone —
     * from its archived copy. A soft delete ({@code DELETE
     * /conversationstore/conversations/{id}} without {@code deletePermanently})
     * archives the descriptor but keeps the memory snapshot, so a missing live
     * descriptor does <em>not</em> mean a missing conversation. This method used to
     * answer {@code null} ("allowed") in that case, which opened every conversation
     * endpoint — raw memory, {@code say} as the owner, SSE, the tool history, the
     * MCP tools and the audit trail — to every authenticated caller as soon as the
     * owner deleted the conversation.
     * <p>
     * When no descriptor exists at all — the conversation was permanently deleted,
     * swept by retention, never existed, or its descriptor was never written — only
     * an admin is let through (so an operator can still reach the leftovers: an
     * orphaned snapshot, the audit trail of a deleted conversation). Everyone else
     * gets a {@link NotFoundException}: there is no owner to compare against, and
     * an unverifiable owner is not an absent one.
     *
     * @return the conversation owner's userId; {@code null} for a legacy unowned
     *         conversation, or for an admin addressing a conversation that has no
     *         descriptor (the caller's actual operation then produces the 404).
     * @throws ForbiddenException
     *             if the caller is neither the owner nor an admin, or if the
     *             descriptor cannot be loaded at all (fail-closed: an unverifiable
     *             owner is not an absent owner).
     * @throws NotFoundException
     *             if no descriptor exists at all and the caller is not an admin
     */
    public String requireConversationOwner(String conversationId) {
        var descriptor = resolveDescriptor(conversationId);
        if (descriptor == null) {
            if (ownershipValidator.isAdmin(identity)) {
                // Also true when authorization is disabled — unchanged behaviour there.
                LOGGER.debugf("No conversation descriptor for %s — admitted as admin", sanitize(conversationId));
                return null;
            }
            LOGGER.debugf("No conversation descriptor for %s — denying non-admin access", sanitize(conversationId));
            throw new NotFoundException("Conversation not found");
        }
        ownershipValidator.requireOwnerOrAdmin(identity, descriptor.getUserId(), RESOURCE_TYPE);
        return descriptor.getUserId();
    }

    /**
     * As {@link #requireConversationOwner}, but a conversation that has no
     * descriptor at all is a {@link NotFoundException} for every caller, admins
     * included.
     * <p>
     * {@code requireConversationOwner} returns null for two different situations:
     * an admin addressing a conversation without a descriptor, or a conversation
     * that exists but is unowned (a legacy row, which {@code OwnershipValidator}
     * deliberately admits). A caller that merely invokes the guard for its side
     * effect therefore cannot tell "no such conversation" from "allowed", and
     * proceeds in both cases. That is harmless where the underlying store 404s on
     * its own, but not for attachments: blobs can outlive the conversation that
     * owned them, so a deleted conversation's id would still reach the store with
     * no ownership established.
     * <p>
     * Use this wherever "the conversation is gone" must stop the request.
     *
     * @return the owner id, which may still be null for a legacy unowned
     *         conversation the caller is allowed to access
     */
    public String requireExistingConversationOwner(String conversationId) {
        var descriptor = resolveDescriptor(conversationId);
        if (descriptor == null) {
            throw new NotFoundException("Conversation not found");
        }
        ownershipValidator.requireOwnerOrAdmin(identity, descriptor.getUserId(), RESOURCE_TYPE);
        return descriptor.getUserId();
    }

    /**
     * The live descriptor, else its archived (soft-deleted) copy, else
     * {@code null}. Both guard variants resolve the owner through this one method
     * so they cannot disagree about whether a conversation still has one.
     *
     * @throws ForbiddenException
     *             when the store fails — fail closed
     */
    private ConversationDescriptor resolveDescriptor(String conversationId) {
        try {
            try {
                var live = conversationDescriptorStore.readDescriptor(conversationId, DESCRIPTOR_VERSION);
                if (live != null) {
                    return live;
                }
            } catch (ResourceNotFoundException e) {
                // Fall through to the archive: a soft delete drops only the live row.
            }
            return conversationDescriptorStore.readDescriptorWithHistory(conversationId, DESCRIPTOR_VERSION);
        } catch (ResourceNotFoundException e) {
            return null;
        } catch (ResourceStoreException e) {
            LOGGER.warnf("Could not load conversation descriptor for ownership check: %s", sanitize(conversationId));
            throw new ForbiddenException("Access denied: unable to verify conversation ownership");
        }
    }

    /**
     * Non-throwing counterpart of {@link #requireConversationOwner} for filtering
     * listings, where a denied entry must be omitted rather than raise. It admits
     * exactly what {@code requireConversationOwner} admits — admin, owner, or an
     * unowned (legacy) conversation to a caller with a principal name — so a caller
     * never lists a conversation they could not read, nor reads one they could not
     * list.
     *
     * @param conversationOwnerId
     *            the owner recorded on the conversation descriptor (may be null)
     */
    public boolean canAccessConversation(String conversationOwnerId) {
        if (ownershipValidator.isAdmin(identity)) {
            // Also true when authorization is disabled — every caller sees everything.
            return true;
        }
        if (conversationOwnerId == null || conversationOwnerId.isBlank()) {
            // Legacy data without ownership — same as requireOwnerOrAdmin, which
            // admits it to everyone except an authenticated caller with no name.
            return !OwnershipValidator.isNamelessCaller(identity);
        }
        return ownershipValidator.isOwner(identity, conversationOwnerId);
    }

    /**
     * Whether the caller sees every conversation regardless of owner — an admin, or
     * anyone at all when authorization is disabled. Lets a listing skip owner
     * filtering (and the over-fetching it needs) entirely.
     */
    public boolean seesAllConversations() {
        return ownershipValidator.isAdmin(identity);
    }

    /**
     * The calling principal's name, to attribute an action on a conversation in the
     * audit trail (G4) — e.g. the {@code hitl.approval} cancellation written when a
     * delete or bulk end terminates a pending approval. Falls back to
     * {@code fallbackActor} when there is no named caller: anonymous, a nameless
     * token, or no request context at all.
     */
    public String callerActor(String fallbackActor) {
        try {
            var principal = identity == null || identity.isAnonymous() ? null : identity.getPrincipal();
            String name = principal == null ? null : principal.getName();
            return name == null || name.isBlank() ? fallbackActor : name;
        } catch (ContextNotActiveException e) {
            return fallbackActor;
        }
    }

    /**
     * Resolves the owner to stamp on a <em>new</em> conversation: the caller's own
     * identity, unless an admin explicitly starts one on another user's behalf.
     * <p>
     * Without this, a conversation started with a {@code null} userId is persisted
     * under a generated {@code anonymous-*} id that matches no principal — it would
     * belong to nobody and {@link #requireConversationOwner} could never admit its
     * own creator.
     *
     * @throws ForbiddenException
     *             if a non-admin caller tries to start a conversation as another
     *             user
     */
    public String resolveOwnerUserId(String requestedUserId) {
        return ownershipValidator.validateAndResolveUserId(identity, requestedUserId);
    }
}
