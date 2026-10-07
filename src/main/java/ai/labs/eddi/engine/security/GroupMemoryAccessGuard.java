/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Who may name a group in a user-memory recall ({@code GET
 * /usermemorystore/memories/{userId}/visible?groupId=…}, MCP
 * {@code get_visible_memories}).
 *
 * <h3>Why naming a group needs a check at all</h3> A group id does two things
 * in a recall: it admits the caller's own group-visible entries tagged with it
 * and, additively, the team-owned {@code group:<id>} lessons a RETRO writes.
 * The first is the caller's own data; the second belongs to whoever runs that
 * group. Taking the ids verbatim let anybody read any team's lessons by naming
 * its group id.
 *
 * <h3>Two answers, by deployment shape</h3>
 * <ul>
 * <li><b>Workspaces enforced</b> — {@code USE} on the group, the level
 * convening it takes ({@link ResourceAccessGuard}).</li>
 * <li><b>Workspaces off</b> — that check admits everybody, and so it used to
 * leave the read open to any {@code eddi-user}. A user is entitled to a group
 * here when they <em>take part</em> in it: they started a discussion of it (own
 * one of its group conversations), or the group names them as a {@code HUMAN}
 * member. Those are the people a RETRO's lessons are written for.</li>
 * </ul>
 * Administrators, and every caller while authorization is disabled, are
 * admitted as everywhere else.
 */
@ApplicationScoped
public class GroupMemoryAccessGuard {

    private static final Logger LOGGER = Logger.getLogger(GroupMemoryAccessGuard.class);

    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;
    private final ResourceAccessGuard resourceAccessGuard;
    private final IGroupConversationStore groupConversationStore;
    private final IAgentGroupStore agentGroupStore;

    @Inject
    public GroupMemoryAccessGuard(SecurityIdentity identity, OwnershipValidator ownershipValidator, ResourceAccessGuard resourceAccessGuard,
            IGroupConversationStore groupConversationStore, IAgentGroupStore agentGroupStore) {
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        this.resourceAccessGuard = resourceAccessGuard;
        this.groupConversationStore = groupConversationStore;
        this.agentGroupStore = agentGroupStore;
    }

    /**
     * Asserts the caller is entitled to every group named; blank entries are
     * skipped.
     *
     * @throws ForbiddenException
     *             naming the first group the caller is not entitled to
     */
    public void requireEntitledToEach(Collection<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty() || ownershipValidator.isAdmin(identity)) {
            return; // isAdmin is also true while authorization is disabled
        }
        if (resourceAccessGuard.settings().isEnforcing()) {
            resourceAccessGuard.requireUseAccessToEach(groupIds, "group");
            return;
        }
        String caller = OwnershipValidator.principalName(identity);
        for (String groupId : groupIds) {
            if (groupId == null || groupId.isBlank()) {
                continue;
            }
            String id = groupId.trim();
            if (caller == null || !takesPart(id, caller)) {
                LOGGER.warnf("Memory recall refused: the caller does not take part in a group it named");
                LOGGER.debugf("Memory recall detail: caller='%s', groupId='%s'", sanitize(caller), sanitize(id));
                throw new ForbiddenException("Access denied: you do not take part in group " + id
                        + " — start a discussion of it, or ask its owner to add you as a member.");
            }
        }
    }

    private boolean takesPart(String groupId, String caller) {
        try {
            if (!groupConversationStore.listByGroupId(groupId, caller, 0, 1).isEmpty()) {
                return true;
            }
        } catch (ResourceStoreException e) {
            // Fail closed: participation that cannot be established is not participation.
            LOGGER.warnf("Could not read group conversations of %s for a memory recall: %s", sanitize(groupId), e.getMessage());
        }
        return isHumanMember(groupId, caller);
    }

    private boolean isHumanMember(String groupId, String caller) {
        try {
            var current = agentGroupStore.getCurrentResourceId(groupId);
            if (current == null) {
                return false;
            }
            AgentGroupConfiguration group = agentGroupStore.read(groupId, current.getVersion());
            if (group == null || group.getMembers() == null) {
                return false;
            }
            for (GroupMember member : group.getMembers()) {
                if (member != null && member.memberType() == MemberType.HUMAN && caller.equals(member.agentId())) {
                    return true;
                }
            }
            return false;
        } catch (ResourceNotFoundException e) {
            return false;
        } catch (ResourceStoreException | RuntimeException e) {
            LOGGER.warnf("Could not read group %s for a memory recall: %s", sanitize(groupId), e.getMessage());
            return false;
        }
    }
}
