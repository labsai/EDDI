import { api } from "@/lib/api-client";
import type { HttpCall } from "@/components/editors/apicalls-editor";

/**
 * Result of discovering endpoints from an OpenAPI spec.
 */
export interface DiscoverEndpointsResult {
  title: string;
  baseUrl: string;
  endpointCount: number;
  groups: Record<string, { targetServerUrl: string; httpCalls: HttpCall[] }>;
}

/**
 * Parse an OpenAPI spec via the backend and return discovered endpoints
 * grouped by tag, with fully generated HttpCall objects.
 *
 * Uses the backend proxy at GET /apicallstore/apicalls/discover-endpoints
 * which handles CORS and uses McpApiToolBuilder for parsing.
 */
export async function discoverEndpoints(
  specUrl: string,
  apiBaseUrl = "",
  apiAuth = "",
): Promise<DiscoverEndpointsResult> {
  const params = new URLSearchParams({ specUrl });
  if (apiBaseUrl) params.set("apiBaseUrl", apiBaseUrl);
  if (apiAuth) params.set("apiAuth", apiAuth);
  return api.get<DiscoverEndpointsResult>(
    `/apicallstore/apicalls/discover-endpoints?${params}`,
  );
}
