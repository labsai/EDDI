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
@OpenAPIDefinition(info = @Info(title = "EDDI API", version = "6.2.0"), tags = {
        // ── Agents ───────────────────────────────────────────────────────
        @Tag(name = "Agents", description = "Agent configuration CRUD"),
        @Tag(name = "Agents / Administration", description = "Deploy, undeploy, trigger, and monitor agents"),
        @Tag(name = "Agents / Groups", description = "Agent group configuration for multi-agent debates"),
        @Tag(name = "Agents / Setup", description = "One-command agent creation and deployment"),
        // ── Configuration ───────────────────────────────────────────────
        @Tag(name = "Configuration / API Calls", description = "HTTP API call definitions for tool use"),
        @Tag(name = "Configuration / Behavior Rules", description = "Behavior rule evaluation configuration"),
        @Tag(name = "Configuration / Dictionary", description = "Dictionary, expressions, and actions for NLP"),
        @Tag(name = "Configuration / Global Variables", description = "Deployment-wide configuration variables"),
        @Tag(name = "Configuration / LLM", description = "LLM provider and model settings"),
        @Tag(name = "Configuration / MCP Calls", description = "MCP server call definitions for tool use"),
        @Tag(name = "Configuration / Output", description = "Output template configuration and action keys"),
        @Tag(name = "Configuration / Prompt Snippets", description = "Reusable system prompt building blocks"),
        @Tag(name = "Configuration / Properties", description = "User property storage and setter configuration"),
        @Tag(name = "Configuration / Workflows", description = "Workflow pipeline configuration and available steps"),
        // ── Conversations ───────────────────────────────────────────────
        @Tag(name = "Conversations", description = "Start, talk to, stream, undo/redo, and manage conversations"),
        @Tag(name = "Conversations / Attachments", description = "Upload and manage binary conversation attachments"),
        @Tag(name = "Conversations / Groups", description = "Multi-agent group discussion orchestration"),
        @Tag(name = "Conversations / Store", description = "Query, delete, and manage conversation history"),
        // ── Integrations ────────────────────────────────────────────────
        @Tag(name = "Integrations / A2A Protocol", description = "Agent-to-Agent protocol endpoints"),
        @Tag(name = "Integrations / Capability Registry", description = "A2A agent capability discovery"),
        @Tag(name = "Integrations / Channels", description = "Channel integration configuration (Slack, Teams, etc.)"),
        @Tag(name = "Integrations / Slack Webhook", description = "Slack Events API webhook receiver"),
        // ── Knowledge ───────────────────────────────────────────────────
        @Tag(name = "Knowledge / RAG Ingestion", description = "RAG document ingestion and indexing"),
        @Tag(name = "Knowledge / RAG Stores", description = "RAG knowledge base configuration"),
        @Tag(name = "Knowledge / User Memory", description = "Persistent user memory management"),
        // ── Operations ──────────────────────────────────────────────────
        @Tag(name = "Operations / Backup", description = "Import and export agents as zip files"),
        @Tag(name = "Operations / Coordinator", description = "Conversation coordinator monitoring and dead letters"),
        @Tag(name = "Operations / Descriptors", description = "Cross-resource document descriptor management"),
        @Tag(name = "Operations / Logs", description = "Real-time log streaming and historical queries"),
        @Tag(name = "Operations / Orphans", description = "Detect and clean up orphaned resources"),
        @Tag(name = "Operations / Schedules", description = "Scheduled agent triggers (heartbeat, cron)"),
        // ── Security ────────────────────────────────────────────────────
        @Tag(name = "Security / Audit Trail", description = "Immutable audit ledger queries"),
        @Tag(name = "Security / Authentication", description = "Authentication status and session management"),
        @Tag(name = "Security / GDPR / Privacy", description = "User data erasure and export for GDPR/CCPA compliance"),
        @Tag(name = "Security / Secrets Vault", description = "Encrypted secret storage and management"),
        @Tag(name = "Security / Tenant Quotas", description = "Per-tenant rate limits and usage metering"),
        // ── Tools ───────────────────────────────────────────────────────
        @Tag(name = "Tools / NLP", description = "Standalone semantic parser"),
        @Tag(name = "Tools / Template Preview", description = "Preview resolved system prompts with sample data"),
        @Tag(name = "Tools / Tool History", description = "Tool execution history, cache, rate limits, and cost tracking"),
        // ── UI ──────────────────────────────────────────────────────────
        @Tag(name = "UI / Chat", description = "Embedded responsive chat window")})
public class OpenApiConfig {
}
