/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupMembersUnresolvableException;

import java.util.List;
import java.util.Objects;

/**
 * Refuses a discussion on a group without members before anything is created or
 * run (live GR4 of the November 2026 review: such a discussion "completed" in
 * 0.1 s with nothing in it). Saving such a group is refused too; this covers
 * groups stored by older versions.
 * <p>
 * A group whose members exist on paper but cannot take a turn (every member an
 * undeployed or unknown agent) still starts: each turn is recorded
 * {@code SKIPPED} under {@code onMemberUnavailable}, and the round ends with
 * {@code outcome: NO_ANSWER}, {@code outcomeReason: NO_MEMBER_ANSWERED} — no
 * longer a plain success (see {@link DiscussionOutcomes}).
 */
public final class GroupMemberPreflight {

    private GroupMemberPreflight() {
    }

    public static void requireMembers(AgentGroupConfiguration config) throws GroupMembersUnresolvableException {
        List<GroupMember> members = config.getMembers() != null ? config.getMembers() : List.of();
        if (members.stream().noneMatch(Objects::nonNull)) {
            throw new GroupMembersUnresolvableException("This group has no members — add at least one member before starting a discussion.");
        }
    }
}
