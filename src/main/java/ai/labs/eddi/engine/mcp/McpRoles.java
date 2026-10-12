/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import java.util.List;

/**
 * The role tiers every MCP tool is checked against — one place, so the MCP
 * surface and the REST surface it mirrors cannot drift apart again.
 * <p>
 * <b>Roles are enumerated, never inherited.</b>
 * {@code SecurityIdentity.hasRole} is a literal check, exactly like JAX-RS
 * {@code @RolesAllowed}, so a single-role requirement of {@code eddi-viewer}
 * refuses an {@code eddi-admin} that was not also granted the viewer role. Each
 * tier therefore lists every role it admits, which gives the MCP read tools the
 * hierarchy {@code eddi-admin ⊇ eddi-editor ⊇
 * eddi-viewer} without changing how a role check works.
 * <p>
 * Each tier mirrors the {@code @RolesAllowed} of the REST resource the tools
 * correspond to; {@code McpRoleTierParityTest} reads both and fails when they
 * disagree. The deliberate differences: {@link #CONVERSE} also admits
 * {@code eddi-viewer}, the MCP read-and-converse role that REST has no
 * counterpart for (every tool in that tier is ownership-guarded, so the viewer
 * only ever reaches its own conversations and memories), and its memory reads
 * admit {@code eddi-editor}, which the REST memory store does not (again only
 * for the caller's own userId); {@link #OBSERVE} admits {@code eddi-viewer}
 * next to the admin; and the {@link #ADMIN_ONLY} schedule and memory-write
 * tools are narrower than the REST stores they touch. The full matrix is
 * documented in {@code docs/mcp-server.md} (Role Mapping).
 */
final class McpRoles {

    static final String ADMIN = "eddi-admin";
    static final String EDITOR = "eddi-editor";
    static final String USER = "eddi-user";
    static final String VIEWER = "eddi-viewer";
    static final String APPROVER = "eddi-approver";

    /**
     * Operator-only: agent setup ({@code IRestAgentSetup}), schedules (these tools
     * address the schedule store directly, without the per-owner scoping REST
     * applies), memory writes for arbitrary users, GDPR, and unscoped server logs.
     */
    static final List<String> ADMIN_ONLY = List.of(ADMIN);

    /**
     * Authoring: the configuration stores, deployment administration
     * ({@code IRestAgentAdministration}), triggers, channels, group configuration —
     * all {@code @RolesAllowed({"eddi-admin", "eddi-editor"})} over REST.
     */
    static final List<String> AUTHOR = List.of(ADMIN, EDITOR);

    /**
     * Conversing and reading what the caller owns: the REST conversation tier
     * ({@code IRestAgentEngine}, {@code IRestGroupConversation}: admin/editor/user)
     * plus {@code eddi-viewer}.
     */
    static final List<String> CONVERSE = List.of(ADMIN, EDITOR, USER, VIEWER);

    /**
     * Diagnostics of a conversation the caller owns: {@code read_audit_trail} and
     * the conversation-scoped {@code read_agent_logs}. The REST counterparts
     * ({@code IRestAuditStore}, {@code IRestLogAdmin}) are admin-only, because an
     * audit entry carries the LLM request — the agent's system prompt, tool calls
     * and their arguments — which {@code eddi-user} cannot read over REST at all.
     * So this tier is <em>not</em> {@link #CONVERSE}: it is {@code eddi-admin} plus
     * {@code eddi-viewer}, the MCP observer role that has always held these two
     * tools (ownership-guarded). {@code eddi-editor} and {@code eddi-user} are
     * deliberately absent; an editor who needs them is granted {@code eddi-viewer}.
     */
    static final List<String> OBSERVE = List.of(ADMIN, VIEWER);

    /** Published documentation — every EDDI role, as {@code IRestDocs}. */
    static final List<String> DOCS = List.of(ADMIN, EDITOR, USER, APPROVER, VIEWER);

    private McpRoles() {
    }
}
