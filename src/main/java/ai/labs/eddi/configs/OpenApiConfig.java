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
        @Tag(name = "Agent Setup", description = "One-command agent creation and deployment"),
        @Tag(name = "Conversations", description = "Start, talk to, stream, undo/redo, and manage conversations"),
        @Tag(name = "Group Conversations", description = "Multi-agent group discussion orchestration"),
        @Tag(name = "Conversation Store", description = "Query, delete, and manage conversation history"),
        @Tag(name = "Agents", description = "Agent configuration CRUD"),
        @Tag(name = "Agent Administration", description = "Deploy, undeploy, trigger, and monitor agents"),
        @Tag(name = "Workflows", description = "Workflow pipeline configuration and available steps"),
        @Tag(name = "LLM Configuration", description = "LLM provider and model settings"),
        @Tag(name = "Behavior Rules", description = "Behavior rule evaluation configuration"),
        @Tag(name = "Dictionary", description = "Dictionary, expressions, and actions for NLP"),
        @Tag(name = "Output", description = "Output template configuration and action keys"),
        @Tag(name = "API Calls", description = "HTTP API call definitions for tool use"),
        @Tag(name = "Properties", description = "User property storage and setter configuration"),
        @Tag(name = "Agent Groups", description = "Agent group configuration for multi-agent debates"),
        @Tag(name = "Backup", description = "Import and export agents as zip files"),
        @Tag(name = "Schedules", description = "Scheduled agent triggers (heartbeat, cron)"),
        @Tag(name = "Secrets Vault", description = "Encrypted secret storage and management"),
        @Tag(name = "Tenant Quotas", description = "Per-tenant rate limits and usage metering"),
        @Tag(name = "Audit Trail", description = "Immutable audit ledger queries"),
        @Tag(name = "GDPR / Privacy", description = "User data erasure and export for GDPR/CCPA compliance"),
        @Tag(name = "Coordinator Admin", description = "Conversation coordinator monitoring and dead letters"),
        @Tag(name = "Orphan Admin", description = "Detect and clean up orphaned resources"),
        @Tag(name = "Log Admin", description = "Real-time log streaming and historical queries"),
        @Tag(name = "Descriptors", description = "Cross-resource document descriptor management"),
        @Tag(name = "Standalone NLP", description = "Standalone semantic parser"),
        @Tag(name = "Chat UI", description = "Embedded responsive chat window")})
public class OpenApiConfig {
}
