/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
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
     *
     * @return the conversation owner's userId, or {@code null} when the descriptor
     *         was not found (the caller's actual operation then produces the 404).
     * @throws ForbiddenException
     *             if the caller is neither the owner nor an admin, or if the
     *             descriptor cannot be loaded at all (fail-closed: an unverifiable
     *             owner is not an absent owner).
     */
    public String requireConversationOwner(String conversationId) {
        try {
            var descriptor = conversationDescriptorStore.readDescriptor(conversationId, 0);
            if (descriptor == null) {
                LOGGER.debugf("Conversation descriptor not found for %s", sanitize(conversationId));
                return null;
            }
            ownershipValidator.requireOwnerOrAdmin(identity, descriptor.getUserId(), RESOURCE_TYPE);
            return descriptor.getUserId();
        } catch (ForbiddenException e) {
            throw e;
        } catch (ResourceNotFoundException e) {
            LOGGER.debugf("Conversation descriptor not found for %s", sanitize(conversationId));
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
     * unowned (legacy) conversation — so a caller never lists a conversation they
     * could not read, nor reads one they could not list.
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
            return true; // legacy data without ownership — same as requireOwnerOrAdmin
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
