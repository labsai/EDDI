/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl;

import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.model.PendingApprovalSummary;
import ai.labs.eddi.engine.security.OwnershipValidator;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Single source of truth for HITL authorization and owner-scoped
 * pending-approval listing, shared by the REST layer ({@code RestAgentEngine} /
 * {@code RestGroupConversation}) and the MCP layer ({@code McpHitlTools}).
 * <p>
 * It <em>composes</em> — it delegates role decisions to
 * {@link OwnershipValidator} and queries to the conversation services; it does
 * not duplicate either. Extracting the fail-closed HITL ownership check into
 * one place keeps "who may decide" from drifting between the REST and MCP
 * surfaces.
 */
@ApplicationScoped
public class HitlAccessGuard {

    private static final Logger LOGGER = Logger.getLogger(HitlAccessGuard.class);

    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final IConversationDescriptorStore conversationDescriptorStore;
    private final IConversationService conversationService;

    @Inject
    public HitlAccessGuard(SecurityIdentity identity,
            OwnershipValidator ownershipValidator,
            IConversationDescriptorStore conversationDescriptorStore,
            IConversationService conversationService) {
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.conversationDescriptorStore = conversationDescriptorStore;
        this.conversationService = conversationService;
    }

    /**
     * Strict HITL ownership check (owner OR admin OR approver). Fail-closed when
     * the descriptor is absent and the caller is neither admin nor approver — a
     * conversation without a verifiable owner may only be decided by
     * admins/approvers.
     *
     * @return the conversation owner's userId, or {@code null} if the descriptor
     *         was not found (the caller then handles the eventual 404 from the
     *         actual operation).
     * @throws ForbiddenException
     *             if the caller may not decide this conversation.
     */
    public String requireConversationHitlAccess(String conversationId) {
        try {
            var descriptor = conversationDescriptorStore.readDescriptor(conversationId, 0);
            ownershipValidator.requireOwnerAdminOrApprover(identity, descriptor.getUserId(), "conversation");
            return descriptor.getUserId();
        } catch (ForbiddenException e) {
            throw e;
        } catch (ResourceNotFoundException e) {
            if (!ownershipValidator.isAdmin(identity) && !ownershipValidator.isApprover(identity)) {
                LOGGER.warnf("HITL operation on conversation %s denied: no descriptor to verify ownership against",
                        sanitize(conversationId));
                throw new ForbiddenException("Access denied: conversation ownership cannot be verified");
            }
            LOGGER.debugf("Conversation descriptor not found for %s", sanitize(conversationId));
            return null;
        } catch (ResourceStoreException e) {
            LOGGER.warnf("Could not load conversation descriptor for HITL ownership check: %s", sanitize(conversationId));
            throw new ForbiddenException("Access denied: unable to verify conversation ownership");
        }
    }

    /**
     * Owner-scoped pending-approval inbox for regular (1:1) conversations: admins
     * and approvers see all; other callers see only their own; anonymous callers
     * see nothing (fail-closed). The owner filter is applied inside the query
     * (before the limit) so a personal inbox is never starved by a large global
     * backlog.
     */
    public List<PendingApprovalSummary> listScopedPendingApprovals(int limit) throws ResourceStoreException {
        if (ownershipValidator.isAdmin(identity) || ownershipValidator.isApprover(identity)) {
            return conversationService.listPendingApprovals(limit);
        }
        String callerId = identity.getPrincipal() != null ? identity.getPrincipal().getName() : null;
        if (callerId == null || callerId.isBlank()) {
            return List.of();
        }
        return conversationService.listPendingApprovals(callerId, limit);
    }
}
