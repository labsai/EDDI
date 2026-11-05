/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.spi;

/**
 * The group an externally-discovered tool belongs to, for
 * {@code discover_tools} under {@code toolLoadingStrategy: LAZY}: for an http
 * tool the apicalls configuration it comes from (one per OpenAPI tag when
 * generated), for an MCP tool its server. Lets the model ask for one group, and
 * read what the groups are, without being sent every tool.
 *
 * @param id
 *            the stable name the model passes back as {@code group}
 * @param description
 *            a one-line summary, or {@code null} when the source has none
 */
public record ToolGroup(String id, String description) {
}
