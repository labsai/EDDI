/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * What a knowledge base's store settings are checked for when it is saved, so a
 * value that can only fail is refused where the author is looking, not at the
 * first ingestion, long after they left.
 *
 * <p>
 * Only values that could never have worked are refused: an unknown store type,
 * a port that is not a port, a pgvector vector size that is not a size, a
 * {@code host} carrying a port or a path, a store URL that is not
 * {@code http(s)}. A value that is a {@code ${...}} reference is resolved only
 * when the store is built and is not judged here. These run on writes
 * ({@link RagConfiguration#validate()}), never on reads, so a configuration
 * stored by an earlier version still loads.
 */
public final class StoreParameterChecks {

    /** The store types {@code EmbeddingStoreFactory} can build. */
    public static final Set<String> SUPPORTED_STORE_TYPES = Set.of("in-memory", "pgvector", "mongodb-atlas", "elasticsearch", "qdrant",
            "chroma");

    /** pgvector's {@code vector} type holds at most this many dimensions. */
    static final int MAX_PGVECTOR_DIMENSION = 16_000;

    private static final Pattern HOSTNAME = Pattern.compile("[A-Za-z0-9_](?:[A-Za-z0-9_.\\-]{0,251}[A-Za-z0-9_])?\\.?");
    /**
     * At least two colons: {@code db:5432} is hex digits and one colon, and is a
     * host with a port.
     */
    private static final Pattern IPV6 = Pattern.compile("\\[?(?=(?:[^:]*:){2})[0-9A-Fa-f:.]+\\]?");

    private StoreParameterChecks() {
    }

    /** The first problem with the store settings, or {@code null}. */
    public static String findProblem(String storeType, Map<String, String> params) {
        if (storeType != null && !SUPPORTED_STORE_TYPES.contains(storeType)) {
            return "Unsupported storeType '" + printable(storeType) + "' (supported: " + new TreeSet<>(SUPPORTED_STORE_TYPES) + ")";
        }
        if (storeType == null || params == null) {
            return null;
        }
        return switch (storeType) {
            case "pgvector" -> firstProblem(port(params, "port"), host(params, "host"), pgvectorDimension(params), foreignTable(params));
            case "qdrant" -> firstProblem(port(params, "port"), host(params, "host"));
            case "elasticsearch" -> httpUrl(params, "serverUrl");
            case "chroma" -> httpUrl(params, "baseUrl");
            default -> null;
        };
    }

    /**
     * An embedding endpoint ({@code baseUrl}, {@code endpoint}) that is a literal
     * must be an {@code http} or {@code https} URL: {@code file:///…} and the like
     * are refused when the model is built, which is long after they were saved.
     */
    public static String findEndpointProblem(Map<String, String> embeddingParameters) {
        if (embeddingParameters == null) {
            return null;
        }
        for (String key : new String[]{"baseUrl", "endpoint"}) {
            String problem = httpUrl(embeddingParameters, key, "embeddingParameters");
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String firstProblem(String... problems) {
        for (String problem : problems) {
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    /**
     * Tables EDDI's own database keeps. A knowledge base pointed at one (when the
     * vector store is EDDI's own PostgreSQL) must never delete from or drop it.
     * This list can drift; the real guard is the check, when a table is used, that
     * it has the columns EDDI's pgvector layout creates.
     */
    static final Set<String> EDDI_TABLES = Set.of(
            "agent_triggers", "alert_events", "alert_rules", "alert_windows", "approval_decisions", "attachments", "audit_ledger",
            "audit_samples", "autonomy_state", "branding", "branding_assets", "connection_grants", "connection_name_claims",
            "connection_oauth_states", "connection_settings", "conversation_checkpoints", "conversation_memories", "conversation_ratings",
            "database_logs", "deployments", "eddi_schedule_fire_logs", "eddi_schedules", "email_mailbox_notices", "email_mailbox_state",
            "email_message_ledger", "email_suppressions", "global_variables", "group_lesson_history", "group_outcomes",
            "hitl_tool_execution_journal", "migration_log", "model_price_catalog", "rag_ingested_files", "rag_ingestion_documents",
            "rag_ingestion_runs", "rag_ingestion_sources", "resources", "resources_history", "secret_vault_deks", "secret_vault_meta",
            "secret_vault_secrets", "slack_hitl_approval_records", "spend_control_changes", "system_settings", "team_budget_months",
            "tenant_quotas", "tenant_usage", "tool_quota_counters", "user_conversations", "user_directory", "usermemories",
            "workspace_notifications", "workspace_settings");

    private static String foreignTable(Map<String, String> params) {
        String table = literal(params.get("table"));
        if (table == null) {
            return null;
        }
        String bare = table.substring(table.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return EDDI_TABLES.contains(bare)
                ? "storeParameters.table '" + bare + "' is one of EDDI's own tables, not a vector table. Name a table of the knowledge "
                        + "base's own, or omit it"
                : null;
    }

    private static String port(Map<String, String> params, String key) {
        String value = literal(params.get(key));
        if (value == null) {
            return null;
        }
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65_535) {
                return null;
            }
        } catch (NumberFormatException e) {
            // Said below.
        }
        return "storeParameters." + key + " must be a port number between 1 and 65535";
    }

    private static String host(Map<String, String> params, String key) {
        String value = literal(params.get(key));
        if (value == null) {
            return null;
        }
        if (HOSTNAME.matcher(value).matches() || IPV6.matcher(value).matches()) {
            return null;
        }
        return "storeParameters." + key + " must be one hostname or IP address, without a port, path or list; "
                + "put the port in storeParameters.port";
    }

    private static String pgvectorDimension(Map<String, String> params) {
        String value = literal(params.get("dimension"));
        if (value == null) {
            return null;
        }
        try {
            int dimension = Integer.parseInt(value);
            if (dimension >= 1 && dimension <= MAX_PGVECTOR_DIMENSION) {
                return null;
            }
        } catch (NumberFormatException e) {
            // Said below.
        }
        return "storeParameters.dimension must be the embedding model's vector size, a whole number between 1 and "
                + MAX_PGVECTOR_DIMENSION;
    }

    private static String httpUrl(Map<String, String> params, String key) {
        return httpUrl(params, key, "storeParameters");
    }

    private static String httpUrl(Map<String, String> params, String key, String owner) {
        String value = literal(params.get(key));
        if (value == null) {
            return null;
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (uri.getHost() != null && scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
        } catch (IllegalArgumentException e) {
            // Said below.
        }
        return owner + "." + key + " must be an http or https URL with a host";
    }

    /**
     * The trimmed value, or null when there is nothing to judge: blank (the default
     * applies) or a reference resolved later.
     */
    private static String literal(String value) {
        if (value == null || value.isBlank() || value.contains("${") || value.contains("{")) {
            return null;
        }
        return value.trim();
    }

    private static String printable(String value) {
        String bounded = value.length() > 40 ? value.substring(0, 40) + "…" : value;
        return bounded.replaceAll("\\p{Cntrl}", "?");
    }
}
