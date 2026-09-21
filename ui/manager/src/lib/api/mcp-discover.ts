import { api } from "@/lib/api-client";

/**
 * Tool metadata returned by the MCP server discover-tools endpoint.
 */
export interface McpToolInfo {
  name: string;
  description?: string;
  parameters?: unknown;
}

export interface DiscoverToolsResult {
  tools: McpToolInfo[];
  count: number;
}

/**
 * The header the probe's credential travels in.
 *
 * Not a query parameter, which is where this used to put it. A credential in a
 * URL is written to ingress logs, reverse-proxy logs, browser history and APM
 * traces before any handler sees it, so EDDI removed the parameter rather than
 * validating it — and now answers 400 to any request that still carries one,
 * naming it, so a client that has not migrated learns what it is doing instead
 * of silently continuing to do it.
 *
 * `X-Mcp-Authorization` rather than `Authorization`, which is already spoken
 * for: that one carries the caller's own Keycloak token to EDDI. This is the
 * credential for the third-party server being probed.
 */
const MCP_AUTHORIZATION_HEADER = "X-Mcp-Authorization";

/**
 * Probe a live MCP server to discover its available tools.
 *
 * `POST /mcpcallsstore/mcpcalls/discover-tools`, with the API key in
 * {@link MCP_AUTHORIZATION_HEADER}.
 */
export async function discoverMcpTools(
  url: string,
  transport = "http",
  apiKey = "",
): Promise<DiscoverToolsResult> {
  // Omitted rather than sent empty: an empty header is a header, and it reaches
  // the probed server as one.
  const headers = apiKey ? { [MCP_AUTHORIZATION_HEADER]: apiKey } : undefined;
  return api.post<DiscoverToolsResult>(
    "/mcpcallsstore/mcpcalls/discover-tools",
    { url, transport },
    headers,
  );
}
