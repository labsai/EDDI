import { describe, it, expect, beforeEach, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { http, HttpResponse } from "msw";
import { server } from "@/test/mocks/server";
import { useActivateOperator, runPostActivationProbes } from "@/hooks/use-operator";
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

describe("useActivateOperator — deterministic checks only; LLM probes moved to background", () => {
  beforeEach(() => {
    server.resetHandlers();
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  it("finishes WITHOUT starting any probe conversation, and reports the dry-run verdict", async () => {
    // The read canary and the live write probe each drive a real model
    // conversation — they were the bulk of the activation wait. Activation now
    // ends at the deterministic checks; if any /agents/:id/start fires before
    // success, a probe leaked back into the blocking path.
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(GOOD_GATE, spy);

    let probeConversationStarted = false;
    server.use(
      http.post("*/administration/operator/gate-dry-run", () =>
        HttpResponse.json({ policyPresent: true, gated: true, matchedPattern: "http.patch:*" }),
      ),
      http.post("*/agents/:agentId/start", () => {
        probeConversationStarted = true;
        return HttpResponse.json(null, { status: 201, headers: { Location: "/agents/conv-x" } });
      }),
    );

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_write" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(probeConversationStarted).toBe(false);
    expect(result.current.data?.policyVerified).toBe(true);
    // The spec is carried in the outcome so runPostActivationProbes can reuse
    // it instead of fetching a copy that may have drifted.
    expect(result.current.data?.spec).toBeTruthy();
    expect(spy.deleted).toBe(false);
  });

  it("still fails closed — rolls back — when the dry-run itself errors (not 404)", async () => {
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(GOOD_GATE, spy);
    server.use(
      http.post("*/administration/operator/gate-dry-run", () => new HttpResponse(null, { status: 500 })),
    );

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_write" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toMatch(/could not verify the approval gate/i);
    await waitFor(() => expect(spy.undeployed && spy.deleted).toBe(true));
  });

  it("proceeds UNVERIFIED — policyVerified false, nothing deleted — on an old backend (dry-run 404)", async () => {
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(GOOD_GATE, spy);
    server.use(
      http.post("*/administration/operator/gate-dry-run", () => new HttpResponse(null, { status: 404 })),
    );

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_write" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.policyVerified).toBe(false);
    expect(spy.deleted).toBe(false);
  });

  it("read_only reports policyVerified null — there is nothing to verify", async () => {
    const spy = { undeployed: false, deleted: false };
    serveProvisioning(GOOD_GATE, spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: config({ scope: "read_only" }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.policyVerified).toBeNull();
  });
});

describe("runPostActivationProbes", () => {
  beforeEach(() => {
    server.resetHandlers();
    vi.spyOn(console, "error").mockImplementation(() => {});
  });

  it("reports the read canary through the callback and never runs a write probe for read_only", async () => {
    // The read canary fails fast here (start 500) — what matters is the
    // callback wiring, not the canary's own logic (tested in its own file).
    server.use(
      http.post("*/agents/:agentId/start", () => new HttpResponse(null, { status: 500 })),
      http.post("*/administration/operator/canary-result", () => new HttpResponse(null, { status: 204 })),
    );

    const readResults: unknown[] = [];
    const writeReports: unknown[] = [];
    await runPostActivationProbes(
      {
        config: { ...config({ scope: "read_only" }), agentId: "op-1", version: 1, enabled: true },
        gate: { verified: true, checkedVersions: [1] },
        policyVerified: null,
        spec: { raw: { openapi: "3.1.0", paths: {} }, paths: {} },
      },
      {
        onReadResult: (r) => readResults.push(r),
        onWriteResult: (r) => writeReports.push(r),
      },
    );

    expect(readResults).toHaveLength(1);
    expect((readResults[0] as { ok: boolean }).ok).toBe(false);
    expect(writeReports).toHaveLength(0);
  });
});
