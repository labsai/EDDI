import { type APIRequestContext, expect } from "@playwright/test";

/**
 * Base URL for API calls through the Vite dev server proxy.
 * The Vite dev server on port 3000 proxies all /botstore, /packagestore, etc.
 * paths to EDDI on localhost:7070.
 */
export const API_BASE = "http://localhost:3000";

/**
 * Poll the EDDI liveness endpoint until the backend is ready.
 * Uses /q/health/live (liveness-only) instead of /q/health to avoid
 * blocking on the @Readiness BotsReadinessHealthCheck which may report
 * DOWN if there's stale deployment state pointing to nonexistent bots.
 */
export async function waitForBackend(
  request: APIRequestContext,
  timeoutMs = 60_000
) {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    try {
      const res = await request.get("http://localhost:7070/q/health/live", {
        timeout: 5000,
      });
      if (res.ok()) {
        const body = await res.json();
        if (body.status === "UP") return;
      }
    } catch {
      // Backend not up yet
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  throw new Error(
    `Backend did not become healthy within ${timeoutMs / 1000}s`
  );
}

/**
 * Parse an eddi:// URI or Location header to extract resource ID and version.
 *
 * Real backend behavior:
 *   CREATE:  "eddi://ai.labs.bot/botstore/bots/abc123"           → { id: "abc123", version: 1 }
 *   UPDATE:  "eddi://ai.labs.bot/botstore/bots/abc123?version=2" → { id: "abc123", version: 2 }
 *   CONV:    "eddi://ai.labs.conversation/conversationstore/conversations/abc123" → { id: "abc123", version: 1 }
 */
export function extractIdFromLocation(location: string): {
  id: string;
  version: number;
} {
  // Strip eddi:// prefix — match anything up to the first store path
  const normalized = location.replace(/^eddi:\/\/[^/]+/, "");

  const url = new URL(normalized, "http://dummy");
  const parts = url.pathname.split("/").filter(Boolean);
  const id = parts[parts.length - 1]!;
  const version = parseInt(url.searchParams.get("version") || "1", 10);
  return { id, version };
}

/**
 * Delete a resource for cleanup. Silently ignores errors.
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
 * Create a bot + package, deploy, and return all IDs for testing.
 * Used by conversation and deployment tests to be fully self-contained.
 */
export async function createAndDeployBot(
  request: APIRequestContext
): Promise<{
  botId: string;
  botVersion: number;
  packageId: string;
  packageVersion: number;
}> {
  // Create package
  const pkgRes = await request.post(`${API_BASE}/packagestore/packages`, {
    data: { packageExtensions: [] },
  });
  expect(pkgRes.status()).toBe(201);
  const pkgLoc = pkgRes.headers()["location"]!;
  const pkg = extractIdFromLocation(pkgLoc);

  // Create bot referencing the package
  const botRes = await request.post(`${API_BASE}/botstore/bots`, {
    data: { packages: [pkgLoc] },
  });
  expect(botRes.status()).toBe(201);
  const botLoc = botRes.headers()["location"]!;
  const bot = extractIdFromLocation(botLoc);

  // Deploy
  const deployRes = await request.post(
    `${API_BASE}/administration/unrestricted/deploy/${bot.id}?version=${bot.version}`
  );
  expect([200, 202]).toContain(deployRes.status());

  // Wait for deployment to complete
  const start = Date.now();
  while (Date.now() - start < 15_000) {
    const statusRes = await request.get(
      `${API_BASE}/administration/unrestricted/deploymentstatus/${bot.id}?version=${bot.version}`
    );
    if (statusRes.ok()) {
      const body = await statusRes.json();
      if (body.status === "READY") break;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }

  return {
    botId: bot.id,
    botVersion: bot.version,
    packageId: pkg.id,
    packageVersion: pkg.version,
  };
}
