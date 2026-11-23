/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.channels.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The typed email control settings of an email integration (email plan v3 §4),
 * stored next to {@code platformConfig} on
 * {@link ChannelIntegrationConfiguration#getEmailControl()}. Maps and nested
 * lists cannot be encoded in the flat string map, so they live here.
 * <p>
 * Today it holds the sender roles of phase C5: {@link #getSenderRoles()} says
 * who a sender is, {@link #getRolePolicies()} what a role may reach. A role can
 * only <em>narrow</em> what the integration and its targets already allow; see
 * {@link EmailSenderRoles}.
 *
 * @since 6.8.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EmailControl {

    private List<SenderRole> senderRoles;
    private Map<String, RolePolicy> rolePolicies;

    public EmailControl() {
    }

    public EmailControl(List<SenderRole> senderRoles, Map<String, RolePolicy> rolePolicies) {
        this.senderRoles = senderRoles;
        this.rolePolicies = rolePolicies;
    }

    /**
     * Who is who, in order: the first entry whose {@code senders} match an
     * authenticated sender gives the role. A sender matching none is role
     * {@value EmailSenderRoles#ROLE_DEFAULT}; an unauthenticated one is always
     * {@value EmailSenderRoles#ROLE_ANONYMOUS}.
     */
    public List<SenderRole> getSenderRoles() {
        return senderRoles != null ? new ArrayList<>(senderRoles) : null;
    }

    public void setSenderRoles(List<SenderRole> senderRoles) {
        this.senderRoles = senderRoles;
    }

    /**
     * What each role may do, by role name. Every role in {@link #getSenderRoles()}
     * needs one; {@value EmailSenderRoles#ROLE_DEFAULT} and
     * {@value EmailSenderRoles#ROLE_ANONYMOUS} may have one.
     */
    public Map<String, RolePolicy> getRolePolicies() {
        return rolePolicies != null ? new LinkedHashMap<>(rolePolicies) : null;
    }

    public void setRolePolicies(Map<String, RolePolicy> rolePolicies) {
        this.rolePolicies = rolePolicies;
    }

    /** Whether any sender role is configured; without one, roles change nothing. */
    public boolean hasRoles() {
        return senderRoles != null && !senderRoles.isEmpty();
    }

    /**
     * One role and the senders it is given to.
     *
     * @param role
     *            the role's name, {@code [a-z0-9][a-z0-9_-]{0,39}}
     * @param senders
     *            sender patterns ({@code user@domain}, {@code *@domain},
     *            {@code *@*.domain}), matched against the authenticated
     *            {@code From} only
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SenderRole(String role, List<String> senders) {
    }

    /**
     * What a role may do. Every field narrows; an absent one inherits.
     *
     * @param targets
     *            names of the integration's targets the role may reach;
     *            {@code ["*"]} or absent for all of them. A mail routed elsewhere
     *            is refused, never rerouted
     * @param tools
     *            the only tool names a turn of this role may call (LLM tools of
     *            every source, {@code mcpcalls} and rule-driven {@code httpcalls}),
     *            intersected with what the agent has; absent for no restriction
     * @param approvalPolicy
     *            reserved for the email approval gate (phase C4); only {@code none}
     *            is accepted until it ships
     * @param sendOut
     *            reserved for send-out (phase C6); only {@code false} is accepted
     *            until it ships
     * @param maxTurnsPerSenderPerDay
     *            at most the integration's {@code maxTurnsPerSenderPerDay}; absent
     *            or {@code 0} inherits it
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RolePolicy(List<String> targets, List<String> tools, String approvalPolicy, Boolean sendOut,
            Integer maxTurnsPerSenderPerDay) {
    }
}
