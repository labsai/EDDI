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
 * disagree. The one deliberate difference is {@link #CONVERSE}: it also admits
 * {@code eddi-viewer}, the MCP read-and-converse role that REST has no
 * counterpart for. Every tool in that tier is ownership-guarded, so the viewer
 * only ever reaches its own conversations and memories. The full matrix is
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

    /** Published documentation — every EDDI role, as {@code IRestDocs}. */
    static final List<String> DOCS = List.of(ADMIN, EDITOR, USER, APPROVER, VIEWER);

    private McpRoles() {
    }
}
