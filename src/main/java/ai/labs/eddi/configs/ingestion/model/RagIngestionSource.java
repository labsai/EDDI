/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion.model;

/**
 * Configuration for a RAG ingestion source — defines how to gather content from
 * a source (web, file system, Git, etc.), and references an existing
 * {@link ai.labs.eddi.configs.rag.model.RagConfiguration} for embedding +
 * storage.
 *
 * <p>
 * The configuration is polymorphic via {@link #sourceConfig()} which contains
 * type-specific settings. The {@link #type()} field determines which
 * implementation of {@link SourceConfig} is present.
 * </p>
 *
 * <p>
 * Stored as a versioned MongoDB document (like {@code RagConfiguration}).
 * Managed via REST API at {@code /ragstore/ingestion-sources/}.
 * </p>
 *
 * @since 6.0.3
 */
public record RagIngestionSource(
        /** Display name for this ingestion source */
        String name,

        /** Human-readable description */
        String description,

        /**
         * Source type identifier. Determines which {@link SourceConfig} implementation
         * is used.
         * <p>
         * Examples: "web" (default), "file", "git", "api".
         */
        String type,

        /**
         * Type-specific source configuration.
         * <p>
         * For "web" type, this is a {@link WebSourceConfig} with startUrl, tocSelector,
         * scope, and crawlSettings.
         */
        SourceConfig sourceConfig,

        /**
         * URI pointing to the {@link ai.labs.eddi.configs.rag.model.RagConfiguration}
         * that defines the embedding model and vector store for this source.
         * <p>
         * Format: {@code eddi://ai.labs.rag/ragstore/rags/{id}?version=1}
         */
        String ragConfigUri,

        /** Ingestion pipeline settings */
        IngestionSettings ingestionSettings,

        /** Schedule configuration */
        Schedule schedule) {

    /**
     * Compact constructor providing default values.
     */
    public RagIngestionSource {
        // Set default type if null
        if (type == null || type.isBlank()) {
            type = "web";
        }

        // Ensure sourceConfig is not null for web type
        if (sourceConfig == null && "web".equals(type)) {
            sourceConfig = new WebSourceConfig(null, null, null, null);
        }

        // Set defaults for other nullable nested configs
        if (ingestionSettings == null) {
            ingestionSettings = new IngestionSettings();
        }
        if (schedule == null) {
            schedule = new Schedule();
        }
    }

    /**
     * Convenience accessor for web source configuration.
     * <p>
     * Returns the {@code sourceConfig} cast to {@link WebSourceConfig}. Only valid
     * when {@code type().equals("web")}.
     *
     * @return the web source configuration
     * @throws ClassCastException
     *             if this is not a web source
     */
    public WebSourceConfig webConfig() {
        if (sourceConfig instanceof WebSourceConfig web) {
            return web;
        }
        throw new ClassCastException("Source type is '" + type + "', not 'web'");
    }

    // --- Inner records ---

    /**
     * Ingestion pipeline settings — chunking, dedup, and content limits.
     */
    public record IngestionSettings(
            /** Chunking strategy: "recursive" (default), "paragraph", "sentence" */
            String chunkStrategy,

            /** Chunk size in characters (default: 512) */
            int chunkSize,

            /** Overlap characters between chunks (default: 64) */
            int chunkOverlap,

            /** Whether to use content-hash deduplication (default: true) */
            boolean contentHashDedup,

            /** Max content length in characters per page (default: 100000) */
            int maxContentLength) {

        /**
         * No-arg constructor for Jackson deserialization.
         */
        public IngestionSettings() {
            this("recursive", 512, 64, true, 100_000);
        }
    }

    /**
     * Schedule configuration for periodic ingestion.
     */
    public record Schedule(
            /** Cron expression (default: daily at 2 AM) */
            String cronExpression,

            /** Whether the schedule is active (default: true) */
            boolean enabled) {

        /**
         * No-arg constructor for Jackson deserialization.
         */
        public Schedule() {
            this("0 2 * * *", true);
        }
    }
}
