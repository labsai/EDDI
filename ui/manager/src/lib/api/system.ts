import { api } from "@/lib/api-client";

interface OpenApiDescriptor {
  info?: {
    version?: string;
  };
}

/**
 * Fetch the EDDI backend version from the OpenAPI spec
 */
export async function getEddiVersion(): Promise<string> {
  try {
    // Relying on Vite proxy for /openapi
    const spec = await api.get<OpenApiDescriptor>("/openapi?format=json");
    return spec?.info?.version || "Unknown";
  } catch (error) {
    console.error("Failed to fetch EDDI version", error);
    return "Unknown";
  }
}
