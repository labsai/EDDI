import { type APIRequestContext, expect } from "@playwright/test";

/**
 * Base URL for API calls through the Vite dev server proxy.
 * The Vite dev server on port 3000 proxies all /botstore, /packagestore, etc.
 * paths to EDDI on localhost:7070.
 */
export const API_BASE = "http://localhost:3000";

/**
 * Poll the EDDI health endpoint until the backend is ready.
 * Tries directly against EDDI (7070) since health check is not proxied.
 */
export async function waitForBackend(
  request: APIRequestContext,
  timeoutMs = 60_000
) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await request.get("http://localhost:7070/q/health");
      if (res.ok()) {
        const body = await res.json();
        if (body.status === "UP") return;
      }
    } catch {
      // Backend not up yet
    }
    await new Promise((r) => setTimeout(r, 2000));
  }
  throw new Error(
    `Backend did not become healthy within ${timeoutMs / 1000}s`
  );
}

/**
 * Parse an eddi:// URI or Location header to extract resource ID and version.
 * Examples:
 *   "eddi://ai.labs.bot/botstore/bots/abc123?version=1" → { id: "abc123", version: 1 }
 *   "/botstore/bots/abc123?version=1" → { id: "abc123", version: 1 }
 */
export function extractIdFromLocation(location: string): {
  id: string;
  version: number;
} {
  const normalized = location
    .replace("eddi://ai.labs.bot", "")
    .replace("eddi://ai.labs.package", "")
    .replace("eddi://ai.labs.behavior", "")
    .replace("eddi://ai.labs.httpcalls", "")
    .replace("eddi://ai.labs.output", "")
    .replace("eddi://ai.labs.regulardictionary", "")
    .replace("eddi://ai.labs.langchain", "")
    .replace("eddi://ai.labs.property", "")
    .replace("eddi://ai.labs.parser", "")
    .replace("eddi://ai.labs.conversation", "");

  const url = new URL(normalized, "http://dummy");
  const parts = url.pathname.split("/").filter(Boolean);
  const id = parts[parts.length - 1]!;
  const version = parseInt(url.searchParams.get("version") || "1", 10);
  return { id, version };
}

/**
 * Delete a resource for cleanup. Silently ignores 404.
 */
export async function cleanupResource(
  request: APIRequestContext,
  storePath: string,
  id: string,
  version: number
) {
  try {
    await request.delete(`${API_BASE}/${storePath}/${id}?version=${version}`);
  } catch {
    // Ignore cleanup failures
  }
}

/**
 * Assert that a response returned a Location header with an eddi:// URI.
 */
export function expectLocationHeader(
  headers: { [key: string]: string },
  storePrefix: string
) {
  const location =
    headers["location"] || headers["Location"] || "";
  expect(location).toContain(storePrefix);
  return location;
}

/** Unique test prefix to identify test-created resources */
export const TEST_PREFIX = `__integration_test_${Date.now()}`;
