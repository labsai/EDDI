/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import org.eclipse.microprofile.openapi.annotations.OpenAPIDefinition;
import org.eclipse.microprofile.openapi.annotations.info.Info;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Controls tag display order in Swagger UI and defines the canonical tag
 * taxonomy. Tags not listed here still appear but are appended at the end.
 *
 * <p>
 * The {@code info} block values are overridden by
 * {@code application.properties} at build time. SmallRye merges both sources.
 * </p>
 *
 * @since 6.0.0
 */
@OpenAPIDefinition(info = @Info(title = "EDDI API", version = "6.0.0"), tags = {
        // ── 01 Core Conversations ───────────────────────────────────────
        @Tag(name = "01. Conversations", description = "Start, talk to, stream, undo/redo, and manage conversations"),
        @Tag(name = "01. Group Conversations", description = "Multi-agent group discussion orchestration"),
        @Tag(name = "01. Conversation Store", description = "Query, delete, and manage conversation history"),
        @Tag(name = "01. Attachments", description = "Upload and manage binary conversation attachments"),
        // ── 02 Agent Management ─────────────────────────────────────────
        @Tag(name = "02. Agent Setup", description = "One-command agent creation and deployment"),
        @Tag(name = "02. Agents", description = "Agent configuration CRUD"),
        @Tag(name = "02. Agent Administration", description = "Deploy, undeploy, trigger, and monitor agents"),
        @Tag(name = "02. Agent Groups", description = "Agent group configuration for multi-agent debates"),
        // ── 03 Agent Configuration ──────────────────────────────────────
        @Tag(name = "03. Workflows", description = "Workflow pipeline configuration and available steps"),
        @Tag(name = "03. LLM Configuration", description = "LLM provider and model settings"),
        @Tag(name = "03. Behavior Rules", description = "Behavior rule evaluation configuration"),
        @Tag(name = "03. Dictionary", description = "Dictionary, expressions, and actions for NLP"),
        @Tag(name = "03. Output", description = "Output template configuration and action keys"),
        @Tag(name = "03. API Calls", description = "HTTP API call definitions for tool use"),
        @Tag(name = "03. MCP Calls", description = "MCP server call definitions for tool use"),
        @Tag(name = "03. Properties", description = "User property storage and setter configuration"),
        @Tag(name = "03. Prompt Snippets", description = "Reusable system prompt building blocks"),
        @Tag(name = "03. Global Variables", description = "Deployment-wide configuration variables"),
        // ── 04 Knowledge & Memory ───────────────────────────────────────
        @Tag(name = "04. RAG Knowledge Bases", description = "RAG knowledge base configuration"),
        @Tag(name = "04. RAG Ingestion", description = "RAG document ingestion and indexing"),
        @Tag(name = "04. User Memory", description = "Persistent user memory management"),
        // ── 05 Tools & Metrics ──────────────────────────────────────────
        @Tag(name = "05. Tool History", description = "Tool execution history, cache, rate limits, and cost tracking"),
        @Tag(name = "05. Template Preview", description = "Preview resolved system prompts with sample data"),
        @Tag(name = "05. Standalone NLP", description = "Standalone semantic parser"),
        // ── 06 Integrations & Protocol ──────────────────────────────────
        @Tag(name = "06. A2A Protocol", description = "Agent-to-Agent protocol endpoints"),
        @Tag(name = "06. Capability Registry", description = "A2A agent capability discovery"),
        @Tag(name = "06. Channel Integrations", description = "Channel integration configuration (Slack, Teams, etc.)"),
        @Tag(name = "06. Slack Webhook", description = "Slack Events API webhook receiver"),
        // ── 07 Security & Compliance ────────────────────────────────────
        @Tag(name = "07. Authentication", description = "Authentication status and session management"),
        @Tag(name = "07. Secrets Vault", description = "Encrypted secret storage and management"),
        @Tag(name = "07. Audit Trail", description = "Immutable audit ledger queries"),
        @Tag(name = "07. GDPR / Privacy", description = "User data erasure and export for GDPR/CCPA compliance"),
        @Tag(name = "07. Tenant Quotas", description = "Per-tenant rate limits and usage metering"),
        // ── 08 Administration ───────────────────────────────────────────
        @Tag(name = "08. Backup", description = "Import and export agents as zip files"),
        @Tag(name = "08. Schedules", description = "Scheduled agent triggers (heartbeat, cron)"),
        @Tag(name = "08. Coordinator Admin", description = "Conversation coordinator monitoring and dead letters"),
        @Tag(name = "08. Orphan Admin", description = "Detect and clean up orphaned resources"),
        @Tag(name = "08. Log Admin", description = "Real-time log streaming and historical queries"),
        @Tag(name = "08. Descriptors", description = "Cross-resource document descriptor management"),
        // ── 09 UI ───────────────────────────────────────────────────────
        @Tag(name = "09. Chat UI", description = "Embedded responsive chat window")})
public class OpenApiConfig {
}
