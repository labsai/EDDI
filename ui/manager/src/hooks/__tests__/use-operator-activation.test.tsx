import { describe, it, expect, beforeEach, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { useActivateOperator } from "@/hooks/use-operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
import { READ_ENDPOINTS, WRITE_ENDPOINTS, parseEndpoint } from "@/lib/operator/tool-scopes";

/**
 * The activation safety gate.
 *
 * `isWriteScopeAvailable` stopped requiring a gate verified on a PREVIOUS
 * operator, on the stated grounds that activation proves the gate about the
 * agent it actually creates. That justification only holds if the read-back is
 * ENFORCED — reporting it and proceeding would leave exactly the hole the old
 * two-step bootstrap existed to close. These tests pin that it is enforced for
 * `read_write` and deliberately not for `read_only`.
 */

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;
const AGENT_ID = "op-new";

/** A gate document that `gateLooksInstalled` accepts. */
const GOOD_GATE = {
  toolApprovals: {
    requireApproval: ["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"],
    exempt: ["http.get:*"],
    timeoutPolicy: "WAIT_INDEFINITELY",
  },
};

/** Present but inert — `requireApproval: []` means the gate is off. */
const BROKEN_GATE = {
  toolApprovals: { requireApproval: [], exempt: [], timeoutPolicy: "WAIT_INDEFINITELY" },
};

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

/**
 * Everything provisioning touches, up to and including the gate read-back.
 * `hitlConfig` decides what the read-back sees, which is the only variable
 * these tests actually manipulate.
 */
function serveProvisioning(hitlConfig: unknown, spy: { undeployed: boolean; deleted: boolean }) {
  server.use(
    http.get("*/openapi", () => HttpResponse.json(fullSpec())),
    http.post("*/administration/agents/setup-api", () =>
      HttpResponse.json({ agentId: AGENT_ID, deployed: true, deploymentStatus: "READY" }, { status: 201 }),
    ),
    http.get("*/agentstore/agents/:id/currentversion", () => HttpResponse.json(1)),
    http.get("*/agentstore/agents/:id", () => HttpResponse.json({ id: AGENT_ID, hitlConfig })),
    http.get("*/administration/:env/deploymentstatus/:agentId", () =>
      HttpResponse.json({ status: "READY" }),
    ),
    http.put(VAR_URL, () => new HttpResponse(null, { status: 204 })),
    http.post("*/administration/:env/undeploy/:agentId", () => {
      spy.undeployed = true;
      return new HttpResponse(null, { status: 200 });
    }),
    http.delete("*/agentstore/agents/:id", () => {
      spy.deleted = true;
      return new HttpResponse(null, { status: 200 });
    }),
    http.delete(VAR_URL, () => new HttpResponse(null, { status: 204 })),
    http.post("*/q/metrics*", () => new HttpResponse(null, { status: 204 })),
  );
}

/**
 * A spec exposing exactly the allow-listed paths, built FROM the allow-list so
 * it cannot drift out of sync with it. `findMissingEndpoints` runs before
 * anything is provisioned, so a hand-written spec that missed one entry would
 * fail these tests at validation and never reach the gate check under test.
 */
function fullSpec() {
  const paths: Record<string, Record<string, unknown>> = {};
  for (const entry of [...READ_ENDPOINTS, ...WRITE_ENDPOINTS]) {
    const parsed = parseEndpoint(entry);
    if (!parsed) continue;
    const methods = (paths[parsed.path] ??= {});
    methods[parsed.method.toLowerCase()] = { operationId: entry };
  }
  return { openapi: "3.0.0", info: { title: "EDDI", version: "6.2.0" }, paths };
}

function config(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return { ...defaultOperatorConfig("Body."), ...overrides };
}

describe("useActivateOperator — gate read-back enforcement", () => {
  beforeEach(() => {
    server.resetHandlers();
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  it("rolls back and rejects a read_write activation whose gate does not verify", async () => {
    // The write canary probes ONE endpoint (a descriptor PATCH), so it can pass
    // while other write patterns are ungated. This read-back is what inspects
    // the whole pattern set — if it is not enforced, a partially gated
    // write-capable operator stays deployed.
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(BROKEN_GATE, spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_write" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toMatch(/approval gate could not be verified/i);
    // Deployed before the check ran, so refusing means tearing it down — not
    // merely reporting and moving on.
    await waitFor(() => expect(spy.undeployed && spy.deleted).toBe(true));
  });

  it("names the reason the read-back gave, so the admin can act on it", async () => {
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(BROKEN_GATE, spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_write" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    // `gateLooksInstalled`'s own wording, threaded through rather than swallowed.
    expect(result.current.error?.message).toMatch(/version 1:/i);
  });

  it("does NOT roll back a read_only activation whose gate does not verify", async () => {
    // An unverified read-only operator is useless, not dangerous — it holds no
    // write tool for a gate to protect. Tearing it down would turn a reportable
    // condition into a failed activation.
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(BROKEN_GATE, spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_only" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isSuccess || result.current.isError).toBe(true));
    expect(result.current.isError).toBe(false);
    expect(result.current.data?.gate.verified).toBe(false);
    expect(spy.deleted).toBe(false);
  });

  it("lets a read_only activation through when the gate does verify", async () => {
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(GOOD_GATE, spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_only" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isSuccess || result.current.isError).toBe(true));
    expect(result.current.isError).toBe(false);
    expect(result.current.data?.gate.verified).toBe(true);
    expect(spy.deleted).toBe(false);
  });
});

describe("useActivateOperator — parallel canaries", () => {
  beforeEach(() => {
    server.resetHandlers();
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  it("aborts the read canary's stream when the write canary rolls the activation back", async () => {
    // Promise.all rejects without cancelling siblings — without the explicit
    // abort, the read canary's SSE fetch would sit pending against a deleted
    // agent until its own timeout, long after activation error handling ended.
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(GOOD_GATE, spy);

    let readStreamAborted = false;
    server.use(
      // The deterministic check fails closed → write canary rolls back and
      // rejects without ever probing.
      http.post("*/administration/operator/gate-dry-run", () => new HttpResponse(null, { status: 500 })),
      // The read canary's conversation: starts fine…
      http.post("*/agents/:agentId/start", () =>
        HttpResponse.json(null, {
          status: 201,
          headers: { Location: "eddi://ai.labs.conversation/conversationstore/conversations/conv-read" },
        }),
      ),
      // …and its stream is held open until the client aborts it.
      http.post("*/agents/:conversationId/stream", async ({ request }) => {
        await new Promise<void>((resolve) => {
          if (request.signal.aborted) {
            readStreamAborted = true;
            resolve();
            return;
          }
          request.signal.addEventListener("abort", () => {
            readStreamAborted = true;
            resolve();
          });
        });
        return new HttpResponse(null, { status: 200 });
      }),
    );

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_write" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    await waitFor(() => expect(readStreamAborted).toBe(true));
    // The rollback itself still happened.
    await waitFor(() => expect(spy.undeployed && spy.deleted).toBe(true));
  });
});
