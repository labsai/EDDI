import { api } from "@/lib/api-client";

interface OpenApiDescriptor {
  info?: {
    version?: string;
  };
}

/**
 * Sentinel for "the backend did not tell us its version".
 *
 * A constant rather than a bare string because callers have to *compare*
 * against it — the update check must never treat it as a version — and it was
 * being spelled out in four files, any one of which could drift.
 */
export const UNKNOWN_VERSION = "Unknown";

/**
 * Fetch the EDDI backend version from the OpenAPI spec
 */
export async function getEddiVersion(): Promise<string> {
  try {
    // Relying on Vite proxy for /openapi
    const spec = await api.get<OpenApiDescriptor>("/openapi?format=json");
    return spec?.info?.version || UNKNOWN_VERSION;
  } catch (error) {
    console.error("Failed to fetch EDDI version", error);
    return UNKNOWN_VERSION;
  }
}
