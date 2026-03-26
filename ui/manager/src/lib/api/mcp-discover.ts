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
 * Probe a live MCP server to discover its available tools.
 * Uses the backend proxy at GET /mcpcallsstore/mcpcalls/discover-tools.
 */
export async function discoverMcpTools(
  url: string,
  transport = "http",
  apiKey = "",
): Promise<DiscoverToolsResult> {
  const params = new URLSearchParams({ url, transport });
  if (apiKey) params.set("apiKey", apiKey);
  return api.get<DiscoverToolsResult>(
    `/mcpcallsstore/mcpcalls/discover-tools?${params}`,
  );
}
