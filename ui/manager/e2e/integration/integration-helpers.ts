import { type APIRequestContext, expect } from "@playwright/test";

/**
 * Base URL for API calls through the Vite dev server proxy.
 * The Vite dev server proxies all /agentstore, /workflowstore, etc. paths to
 * EDDI on localhost:7070.
 *
 * Honours `PORT` for the same reason `playwright.config.ts` and
 * `vite.config.ts` do. Hardcoding 3000 here while the config had moved would
 * have been the worse half of the bug it fixed: the run would drive a dev
 * server on one port and send its API calls to whatever happened to be on
 * 3000 — another worktree's server, most likely — and the mismatch would
 * surface as unexplained data, not as a connection error.
 */
export const API_BASE = `http://localhost:${Number(process.env.PORT) || 3000}`;

/**
 * Poll the EDDI liveness endpoint until the backend is ready.
 * Uses /q/health/live (liveness-only) instead of /q/health to avoid
 * blocking on the @Readiness AgentsReadinessHealthCheck which may report
 * DOWN if there's stale deployment state pointing to nonexistent agents.
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
 *   CREATE:  "eddi://ai.labs.agent/agentstore/agents/abc123"           → { id: "abc123", version: 1 }
 *   UPDATE:  "eddi://ai.labs.agent/agentstore/agents/abc123?version=2" → { id: "abc123", version: 2 }
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
 * Create a agent + package, deploy, and return all IDs for testing.
 * Used by conversation and deployment tests to be fully self-contained.
 */
export async function createAndDeployAgent(
  request: APIRequestContext
): Promise<{
  agentId: string;
  agentVersion: number;
  workflowId: string;
  workflowVersion: number;
}> {
  // Create package
  const pkgRes = await request.post(`${API_BASE}/workflowstore/workflows`, {
    data: { workflowSteps: [] },
  });
  expect(pkgRes.status()).toBe(201);
  const pkgLoc = pkgRes.headers()["location"]!;
  const pkg = extractIdFromLocation(pkgLoc);

  // Create agent referencing the package
  const agentRes = await request.post(`${API_BASE}/agentstore/agents`, {
    data: { workflows: [pkgLoc] },
  });
  expect(agentRes.status()).toBe(201);
  const agentLoc = agentRes.headers()["location"]!;
  const agent = extractIdFromLocation(agentLoc);

  // Deploy
  const deployRes = await request.post(
    `${API_BASE}/administration/production/deploy/${agent.id}?version=${agent.version}`
  );
  expect([200, 202]).toContain(deployRes.status());

  // Wait for deployment to complete.
  //
  // The assertion at the end is the point. This loop used to fall out of its
  // 15s budget and return anyway, so an agent that never deployed was handed
  // back as if it had — and the failure surfaced much later as a bare `404`
  // from `POST /agents/{id}/start` in whichever test used it, naming
  // neither deployment nor this helper. Every caller depends on READY, so not
  // reaching it is this function's failure to report.
  const DEPLOY_TIMEOUT_MS = 30_000;
  const start = Date.now();
  let status = "UNKNOWN";
  while (Date.now() - start < DEPLOY_TIMEOUT_MS) {
    const statusRes = await request.get(
      `${API_BASE}/administration/production/deploymentstatus/${agent.id}?version=${agent.version}`
    );
    if (statusRes.ok()) {
      const body = await statusRes.json();
      status = body.status ?? "UNKNOWN";
      if (status === "READY") break;
    }
    await new Promise((r) => setTimeout(r, 1000));
  }
  expect(
    status,
    `agent ${agent.id} v${agent.version} did not reach READY within ${DEPLOY_TIMEOUT_MS / 1000}s ` +
      `(last status: ${status}) — everything downstream of this helper depends on it`,
  ).toBe("READY");

  return {
    agentId: agent.id,
    agentVersion: agent.version,
    workflowId: pkg.id,
    workflowVersion: pkg.version,
  };
}
