/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.mcpcalls.model;

/**
 * Body of a {@code POST /mcpcallsstore/mcpcalls/discover-tools} probe.
 * <p>
 * Deliberately carries no credential: the API key travels in the
 * {@code X-Mcp-Authorization} header instead. A credential in a query string —
 * which is what the superseded {@code GET} form accepted — is written to
 * ingress logs, reverse-proxy logs, browser history and APM traces before any
 * handler sees it, so rejecting it at the handler would not remove the leak.
 *
 * @param url
 *            the MCP server to probe
 * @param transport
 *            {@code http} (default) or a supported alias
 */
public record McpToolDiscoveryRequest(String url, String transport) {
}
