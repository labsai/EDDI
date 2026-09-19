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
 * Reconfiguring the operator REPLACES its agent — `setup-api` always builds a new
 * id — so the previous one has to be retired, and a retirement that fails has to
 * be reported.
 *
 * The incident: a reconfigure that changed only the model left two operators on
 * the instance, both `READY`. The retirement had failed silently, because it
 * undeployed without `endAllActiveConversations` (the backend answers 409 while a
 * conversation is open — and the admin's own operator chat, on the same screen as
 * the Reconfigure button, is almost always open) and the caller then swallowed the
 * delete failure in a bare `catch {}`. The UI addressed the new agent while saying
 * nothing about it, so the engineer debugging the operator repaired the abandoned
 * one and the symptom did not move.
 */

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;
const OLD_AGENT = "op-old";
const NEW_AGENT = "op-new";

const GOOD_GATE = {
  toolApprovals: {
    requireApproval: ["http.post:*", "http.put:*", "http.patch:*", "http.delete:*"],
    exempt: ["http.get:*"],
    timeoutPolicy: "WAIT_INDEFINITELY",
  },
};

function wrapper({ children }: { children: React.ReactNode }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}

function fullSpec() {
  const paths: Record<string, Record<string, unknown>> = {};
  for (const entry of [...READ_ENDPOINTS, ...WRITE_ENDPOINTS]) {
    const parsed = parseEndpoint(entry);
    if (!parsed) continue;
    const methods = (paths[parsed.path] ??= {});
    methods[parsed.method.toLowerCase()] = { operationId: entry };
  }
  return { openapi: "3.0.0", info: { title: "EDDI", version: "6.4.0" }, paths };
}

interface Retirement {
  /** Every undeploy seen, as `agentId` plus the query string it carried. */
  undeploys: { agentId: string; query: string }[];
  deletes: string[];
}

/**
 * @param deleteOldFails
 *            when true the DELETE of the superseded agent answers 409, which is
 *            the shape the real failure took.
 */
function serveReconfigure(spy: Retirement, deleteOldFails = false) {
  server.use(
    http.get("*/openapi", () => HttpResponse.json(fullSpec())),
    http.post("*/administration/agents/setup-api", () =>
      HttpResponse.json({ agentId: NEW_AGENT, deployed: true, deploymentStatus: "READY" }, { status: 201 }),
    ),
    http.get("*/agentstore/agents/:id/currentversion", () => HttpResponse.json(1)),
    http.get("*/agentstore/agents/:id", ({ params }) =>
      HttpResponse.json({ id: params.id, hitlConfig: GOOD_GATE }),
    ),
    http.get("*/administration/:env/deploymentstatus/:agentId", () => HttpResponse.json({ status: "READY" })),
    http.put(VAR_URL, () => new HttpResponse(null, { status: 204 })),
    http.post("*/administration/:env/undeploy/:agentId", ({ params, request }) => {
      spy.undeploys.push({
        agentId: String(params.agentId),
        query: new URL(request.url).search,
      });
      return new HttpResponse(null, { status: 200 });
    }),
    http.delete("*/agentstore/agents/:id", ({ params }) => {
      const id = String(params.id);
      spy.deletes.push(id);
      if (deleteOldFails && id === OLD_AGENT) {
        return HttpResponse.text("agent still has active conversations", { status: 409 });
      }
      return new HttpResponse(null, { status: 200 });
    }),
    http.delete(VAR_URL, () => new HttpResponse(null, { status: 204 })),
  );
}

function existingOperator(overrides: Partial<OperatorConfig> = {}): OperatorConfig {
  return {
    ...defaultOperatorConfig("Body."),
    scope: "read_only",
    enabled: true,
    agentId: OLD_AGENT,
    version: 2,
    apiBaseUrl: "http://127.0.0.1:7070",
    ...overrides,
  };
}

function freshSpy(): Retirement {
  return { undeploys: [], deletes: [] };
}

describe("useActivateOperator — retiring the superseded operator", () => {
  beforeEach(() => {
    server.resetHandlers();
    vi.spyOn(console, "error").mockImplementation(() => {});
    vi.spyOn(console, "warn").mockImplementation(() => {});
  });

  /**
   * The specific reason retirement failed. Without the flag, an operator that had
   * been USED could not be retired at all: the 409 killed the undeploy and the
   * delete that followed hit a still-deployed agent.
   */
  it("ends the superseded operator's conversations so its undeploy cannot 409", async () => {
    const spy = freshSpy();
    serveReconfigure(spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({ agentName: "EDDI Platform Operator", config: existingOperator(), apiKey: "sk-test" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const retired = spy.undeploys.find((entry) => entry.agentId === OLD_AGENT);
    expect(retired, "the superseded agent must be undeployed").toBeDefined();
    expect(retired?.query).toContain("endAllActiveConversations=true");
  });

  it("deletes the superseded agent and reports no warning when that works", async () => {
    const spy = freshSpy();
    serveReconfigure(spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({ agentName: "EDDI Platform Operator", config: existingOperator(), apiKey: "sk-test" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(spy.deletes).toContain(OLD_AGENT);
    expect(result.current.data?.supersededWarning).toBeNull();
    expect(result.current.data?.config.agentId).toBe(NEW_AGENT);
  });

  /**
   * THE regression test for defect 2's silence. A failed retirement must reach the
   * caller — two deployed operators with nothing said about it is the state that
   * wasted a debugging session.
   */
  it("reports a failed retirement instead of swallowing it", async () => {
    const spy = freshSpy();
    serveReconfigure(spy, true);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({ agentName: "EDDI Platform Operator", config: existingOperator(), apiKey: "sk-test" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const warning = result.current.data?.supersededWarning;
    expect(warning, "a failed retirement must be reported").toBeTruthy();
    // Both ids, because the whole point is telling the admin WHICH agent is live
    // and which one is the stale leftover.
    expect(warning).toContain(OLD_AGENT);
    expect(warning).toContain(NEW_AGENT);
    expect(warning).toMatch(/may still be deployed/i);
  });

  /**
   * A failed retirement leaves the NEW operator live and saved; it is not an
   * activation failure. Turning it into one would roll back a working operator
   * over a stale leftover.
   */
  it("still activates the new operator when the old one cannot be removed", async () => {
    const spy = freshSpy();
    serveReconfigure(spy, true);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({ agentName: "EDDI Platform Operator", config: existingOperator(), apiKey: "sk-test" });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data?.config.enabled).toBe(true);
    expect(result.current.data?.config.agentId).toBe(NEW_AGENT);
  });

  /**
   * A config that recorded the agent but not its version cannot be undeployed or
   * deleted (both endpoints need it). removeSupersededAgent returns silently for
   * it, so without an explicit report this path still left two operators running.
   */
  it("reports a predecessor whose version was never recorded instead of skipping it silently", async () => {
    const spy = freshSpy();
    serveReconfigure(spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: existingOperator({ version: null }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const warning = result.current.data?.supersededWarning;
    expect(warning).toContain(OLD_AGENT);
    expect(warning).toMatch(/no recorded version/i);
  });

  /** A first activation has nothing to retire, and must not report as if it had. */
  it("retires nothing on a first activation", async () => {
    const spy = freshSpy();
    serveReconfigure(spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: existingOperator({ agentId: null, version: null, enabled: false }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(spy.deletes).not.toContain(OLD_AGENT);
    expect(result.current.data?.supersededWarning).toBeNull();
  });

  /**
   * The resolved base URL has to be PERSISTED on the config, not just sent: the
   * status panel reads it back to show which address the live tools call, and a
   * later reconfigure reuses it instead of re-deriving.
   */
  it("persists the resolved platform base URL onto the saved config", async () => {
    const spy = freshSpy();
    serveReconfigure(spy);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({
      agentName: "EDDI Platform Operator",
      config: existingOperator({ apiBaseUrl: null }),
      apiKey: "sk-test",
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // From the backend's self-url endpoint, not from the test origin.
    expect(result.current.data?.config.apiBaseUrl).toBe("http://127.0.0.1:7070");
  });

  /**
   * A reconfigure whose replacement fails the gate read-back (read_write, no gate
   * on the new document). The store is stateful so the helper sees what the
   * server would: a deleted agent 404s, a cleared variable 404s.
   *
   * @param variableDeleteFails the DELETE of the config variable answers 500 —
   *            the replacement is gone but its config is still readable.
   * @param replacementDeleteFails the DELETE of the new agent answers 500 — the
   *            rollback failed and the replacement is still there.
   */
  function failVerification(
    spy: Retirement,
    predecessor: OperatorConfig,
    { variableDeleteFails = false, replacementDeleteFails = false } = {},
  ) {
    const writes: OperatorConfig[] = [];
    let stored: string | null = JSON.stringify(predecessor);
    server.use(
      http.get("*/agentstore/agents/:id", ({ params }) =>
        spy.deletes.includes(String(params.id)) && !(replacementDeleteFails && params.id === NEW_AGENT)
          ? HttpResponse.text("not found", { status: 404 })
          : HttpResponse.json({ id: params.id }),
      ),
      http.delete("*/agentstore/agents/:id", ({ params }) => {
        spy.deletes.push(String(params.id));
        return replacementDeleteFails && params.id === NEW_AGENT
          ? HttpResponse.text("boom", { status: 500 })
          : new HttpResponse(null, { status: 200 });
      }),
      http.get(VAR_URL, () =>
        stored === null
          ? HttpResponse.text("not found", { status: 404 })
          : HttpResponse.json({ key: OPERATOR_VARIABLE_KEY, value: stored }),
      ),
      http.put(VAR_URL, async ({ request }) => {
        const body = (await request.json()) as { value: string };
        stored = body.value;
        writes.push(JSON.parse(body.value) as OperatorConfig);
        return new HttpResponse(null, { status: 204 });
      }),
      http.delete(VAR_URL, () => {
        if (variableDeleteFails) return HttpResponse.text("boom", { status: 500 });
        stored = null;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    return writes;
  }

  /**
   * Retirement waits for verification. A replacement that fails its gate check is
   * rolled back — and if the predecessor had already been retired by then, the
   * deployment was left with no operator at all. It must instead still be
   * deployed, and the config handed back to it.
   */
  it("keeps the predecessor and restores its config when the replacement fails verification", async () => {
    const spy = freshSpy();
    serveReconfigure(spy);
    const predecessor = existingOperator({ scope: "read_write" });
    const writes = failVerification(spy, predecessor);

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({ agentName: "EDDI Platform Operator", config: predecessor, apiKey: "sk-test" });

    await waitFor(() => expect(result.current.isError).toBe(true));
    // The replacement was torn down; the predecessor was not touched.
    expect(spy.deletes).toContain(NEW_AGENT);
    expect(spy.deletes).not.toContain(OLD_AGENT);
    expect(spy.undeploys.map((entry) => entry.agentId)).not.toContain(OLD_AGENT);
    // And the config points at the predecessor again.
    expect(writes[writes.length - 1]?.agentId).toBe(OLD_AGENT);
    expect(result.current.error?.message).toContain(OLD_AGENT);
    expect(result.current.error?.message).toMatch(/still the active one/i);
  });

  /**
   * The rollback deleted the replacement but could not clear the variable, so the
   * stored config still names an agent that no longer exists. Read as "the
   * replacement is live", that retired the predecessor and left no operator.
   */
  it("restores the predecessor when the replacement is gone but its config could not be cleared", async () => {
    const spy = freshSpy();
    serveReconfigure(spy);
    const predecessor = existingOperator({ scope: "read_write" });
    const writes = failVerification(spy, predecessor, { variableDeleteFails: true });

    const { result } = renderHook(() => useActivateOperator(), { wrapper });
    result.current.mutate({ agentName: "EDDI Platform Operator", config: predecessor, apiKey: "sk-test" });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(spy.deletes).not.toContain(OLD_AGENT);
    expect(writes[writes.length - 1]?.agentId).toBe(OLD_AGENT);
  });

  /**
   * The replacement's rollback failed, so it is still there. Activation failed,
   * so the predecessor is not destroyed on top of that — both are named instead.
   * Holds for a predecessor with no recorded version too, which used to be
   * skipped without a word.
   */
  it.each([2, null])(
    "never retires the predecessor (version %s) when the replacement is still present",
    async (version) => {
      const spy = freshSpy();
      serveReconfigure(spy);
      const predecessor = existingOperator({ scope: "read_write", version });
      failVerification(spy, predecessor, { replacementDeleteFails: true });

      const { result } = renderHook(() => useActivateOperator(), { wrapper });
      result.current.mutate({ agentName: "EDDI Platform Operator", config: predecessor, apiKey: "sk-test" });

      await waitFor(() => expect(result.current.isError).toBe(true));
      expect(spy.deletes).not.toContain(OLD_AGENT);
      expect(spy.undeploys.map((entry) => entry.agentId)).not.toContain(OLD_AGENT);
      expect(result.current.error?.message).toContain(OLD_AGENT);
      expect(result.current.error?.message).toContain(NEW_AGENT);
    },
  );
});
