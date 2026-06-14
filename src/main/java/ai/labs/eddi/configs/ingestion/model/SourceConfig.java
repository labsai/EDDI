/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.ingestion.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Polymorphic configuration for ingestion source types.
 * <p>
 * Implementations define source-type-specific settings (e.g., URLs for web,
 * paths for file system, repo info for Git). The top-level
 * {@link RagIngestionSource} holds common settings like name, schedule, and
 * chunking configuration.
 * <p>
 * Jackson uses type deduction based on the discriminating fields present in the
 * JSON. Each subtype has a unique field signature that Jackson can detect.
 *
 * @since 6.0.3
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.DEDUCTION)
@JsonSubTypes({
        @JsonSubTypes.Type(WebSourceConfig.class)
})
public sealed interface SourceConfig permits WebSourceConfig {
}
