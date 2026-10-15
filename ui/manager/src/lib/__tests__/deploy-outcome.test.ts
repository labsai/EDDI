import { describe, it, expect, vi, afterEach } from "vitest";
import { http, HttpResponse } from "msw";
import { toast } from "sonner";
import type { TFunction } from "i18next";
import { server } from "@/test/mocks/server";
import { fixGrantAndRedeploy } from "@/lib/deploy-outcome";
import { VAULT_GRANT_MISSING, type DeploymentFailure } from "@/lib/api/agents";

const t = ((key: string, fallback?: unknown) => (typeof fallback === "string" ? fallback : key)) as unknown as TFunction;

const FAILURE: DeploymentFailure = {
  code: VAULT_GRANT_MISSING,
  message: "Agent 'a1' is not granted vault key 'k'",
  secrets: [],
};

describe("fixGrantAndRedeploy", () => {
  afterEach(() => vi.restoreAllMocks());

  it("turns a failed deploy request into a failed outcome and a toast, never a rejection", async () => {
    server.use(
      http.post("*/administration/:env/deploy/:agentId", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
    );
    const error = vi.spyOn(toast, "error");
    const settled = vi.fn();

    // No dialog host is mounted, so the grant question resolves without asking.
    const outcome = await fixGrantAndRedeploy(
      { agentId: "a1", version: 1, environment: "production", failure: FAILURE },
      t,
      settled,
    );

    expect(outcome.kind).toBe("failed");
    expect(error).toHaveBeenCalled();
    expect(settled).toHaveBeenCalled();
  });
});
