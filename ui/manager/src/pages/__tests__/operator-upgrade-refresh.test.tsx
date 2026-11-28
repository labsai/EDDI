import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@eddi/app-kit/test/test-utils";
import { http, HttpResponse, delay } from "msw";
import { focusManager } from "@tanstack/react-query";
import { server } from "@eddi/app-kit/test/mocks/server";
import { OperatorPage } from "../operator";
import { defaultOperatorConfig, OPERATOR_VARIABLE_KEY } from "@/lib/api/operator";
import type { OperatorConfig } from "@/lib/api/operator";
import { useOperatorChatStore } from "@/hooks/use-operator-chat";
import { useGrantDialogStore } from "@eddi/app-kit/hooks/use-deploy-with-grants";
import { READ_ENDPOINTS, WRITE_ENDPOINTS, buildToolApprovals, endpointsForScope, parseEndpoint } from "@/lib/operator/tool-scopes";
import { defaultOperatorPromptBody } from "@/lib/operator/system-prompt";
import { OPERATOR_DESCRIPTOR_DESCRIPTION } from "@/lib/api/operator-marker";

/**
 * The operator upgrade end to end on the page, with the REAL activation hook
 * against a stateful mock backend: an operator provisioned at an old revision,
 * whose instructions are a trimmed default and whose agent holds the model
 * key's vault grant, upgraded with one click.
 *
 * Every regression from the field is asserted here:
 *  (a) the dialog preselects replacing the trimmed instructions;
 *  (b) the grant moves to the new agent before its deploy — no grant dialog,
 *      so no "allow every agent";
 *  (d) the page never shows "switched on, but its agent is not deployed" for
 *      the predecessor it is retiring, and shows the new agent as deployed
 *      without a reload.
 * (c), the deleted predecessor leaving the grant, is the backend's job
 * (RestAgentStore → VaultGrantService) and is asserted by its unit tests and by
 * the real-backend operator E2E.
 */

vi.mock("@eddi/app-kit/hooks/use-auth", () => ({
  useAuth: () => ({
    authenticated: true,
    loading: false,
    user: null,
    roles: ["eddi-admin"],
    method: "none" as const,
    login: () => {},
    logout: () => {},
  }),
  useHasRole: () => true,
}));

// The LLM probes run after activation and need a model; they are not under test.
vi.mock("@/hooks/use-operator", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/hooks/use-operator")>();
  return { ...actual, runPostActivationProbes: vi.fn(() => Promise.resolve()) };
});

const VAR_URL = `*/variablestore/variables/default/${OPERATOR_VARIABLE_KEY}`;
const OLD_AGENT = "aaaaaaaaaaaaaaaaaaaaaa01";
const NEW_AGENT = "bbbbbbbbbbbbbbbbbbbbbb02";
const KEY = "operator-llm-key";
const SCOPE = "read_only" as const;

function fullSpec() {
  const paths: Record<string, Record<string, unknown>> = {};
  for (const entry of [...READ_ENDPOINTS, ...WRITE_ENDPOINTS]) {
    const parsed = parseEndpoint(entry);
    if (!parsed) continue;
    (paths[parsed.path] ??= {})[parsed.method.toLowerCase()] = { operationId: entry };
  }
  return { openapi: "3.0.0", info: { title: "EDDI", version: "6.7.1" }, paths };
}

function trimmedDefault(): string {
  return defaultOperatorPromptBody(SCOPE)
    .split("\n")
    .filter((_, i) => i % 4 !== 0)
    .join("\n");
}

interface Backend {
  config: OperatorConfig;
  grant: string[];
  oldUndeployed: boolean;
  oldDeleted: boolean;
  newDeployed: boolean;
  setupPrompts: string[];
}

function serveBackend(state: Backend) {
  const exists = (id: string) => (id === OLD_AGENT ? !state.oldDeleted : id === NEW_AGENT && state.setupPrompts.length > 0);
  server.use(
    http.get(VAR_URL, async () => {
      // A slow config read is what let the predecessor's status refresh win the
      // race and paint the stale banner; it is slowed here to make that window wide.
      if (state.config.agentId === NEW_AGENT) await delay(150);
      return HttpResponse.json({ key: OPERATOR_VARIABLE_KEY, value: JSON.stringify(state.config) });
    }),
    http.put(VAR_URL, async ({ request }) => {
      const body = (await request.json()) as { value: string };
      state.config = JSON.parse(body.value) as OperatorConfig;
      return new HttpResponse(null, { status: 204 });
    }),
    http.get("*/openapi", () => HttpResponse.json(fullSpec())),
    http.post("*/administration/agents/setup-api", async ({ request }) => {
      const body = (await request.json()) as { systemPrompt?: string };
      state.setupPrompts.push(body.systemPrompt ?? "");
      return HttpResponse.json({ agentId: NEW_AGENT, deployed: false }, { status: 201 });
    }),
    http.get("*/agentstore/agents/:id/currentversion", ({ params }) =>
      exists(String(params.id)) ? HttpResponse.json(1) : HttpResponse.text("not found", { status: 404 }),
    ),
    http.get("*/agentstore/agents/:id", ({ params }) =>
      exists(String(params.id))
        ? HttpResponse.json({ hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", toolApprovals: buildToolApprovals() } })
        : HttpResponse.text("not found", { status: 404 }),
    ),
    http.get("*/descriptorstore/descriptors/:id", ({ params }) =>
      HttpResponse.json({
        resource: `eddi://ai.labs.agent/agentstore/agents/${String(params.id)}?version=1`,
        name: "EDDI Platform Operator",
        description: OPERATOR_DESCRIPTOR_DESCRIPTION,
        createdOn: 0,
        lastModifiedOn: 0,
      }),
    ),
    http.get("*/administration/:env/deploymentstatus/:agentId", ({ params }) => {
      const id = String(params.agentId);
      const live = id === OLD_AGENT ? !state.oldUndeployed : id === NEW_AGENT && state.newDeployed;
      return HttpResponse.json(live ? { status: "READY" } : { status: "NOT_FOUND" });
    }),
    http.get("*/administration/:env/deploy/:agentId/preflight", ({ params }) => {
      const id = String(params.agentId);
      const granted = state.grant.includes(id);
      return HttpResponse.json({
        agentId: id,
        version: 1,
        enforcement: "ENFORCE",
        checked: true,
        ready: granted,
        grantIssues: granted
          ? []
          : [{ tenantId: "default", keyName: KEY, reference: `\${vault:${KEY}}`, allowedAgents: [...state.grant] }],
      });
    }),
    http.post("*/administration/:env/deploy/:agentId", ({ params }) => {
      const id = String(params.agentId);
      if (!state.grant.includes(id)) {
        return HttpResponse.json({
          status: "ERROR",
          agentId: id,
          version: 1,
          environment: "production",
          failure: { code: "VAULT_GRANT_MISSING", message: "not granted", secrets: [{ tenantId: "default", keyName: KEY, reference: `\${vault:${KEY}}` }] },
        });
      }
      if (id === NEW_AGENT) state.newDeployed = true;
      return HttpResponse.json({ status: "READY", agentId: id, version: 1, environment: "production" });
    }),
    http.post("*/administration/:env/undeploy/:agentId", ({ params }) => {
      if (String(params.agentId) === OLD_AGENT) state.oldUndeployed = true;
      return new HttpResponse(null, { status: 200 });
    }),
    http.delete("*/agentstore/agents/:id", async ({ params }) => {
      if (String(params.id) === OLD_AGENT) {
        state.oldDeleted = true;
        // The admin returns to the tab while the upgrade is still finishing: every
        // stale query refetches, the predecessor's deployment status among them —
        // in the field the 15-second status poll did the same. The response is
        // held back so that refetch lands while the upgrade is still pending.
        focusManager.setFocused(false);
        focusManager.setFocused(true);
        await delay(300);
      }
      return new HttpResponse(null, { status: 200 });
    }),
    http.get("*/secretstore/secrets/default", () =>
      HttpResponse.json([{ tenantId: "default", keyName: KEY, allowedAgents: [...state.grant], createdAt: "", lastAccessedAt: null, lastRotatedAt: null, checksum: "x", description: null }]),
    ),
    http.get(`*/secretstore/secrets/default/${KEY}`, () =>
      HttpResponse.json({ tenantId: "default", keyName: KEY, allowedAgents: [...state.grant], createdAt: "", lastAccessedAt: null, lastRotatedAt: null, checksum: "x", description: null }),
    ),
    http.post(`*/secretstore/secrets/default/${KEY}/grant/agents/:agentId`, ({ params }) => {
      const id = String(params.agentId);
      const before = [...state.grant];
      if (!state.grant.includes(id)) state.grant.push(id);
      return HttpResponse.json({
        reference: `\${vault:${KEY}}`,
        tenantId: "default",
        keyName: KEY,
        dryRun: false,
        allowedAgents: [...state.grant],
        previousAllowedAgents: before,
        grantsAllAgents: false,
        agentsLosingAccess: [],
        changed: true,
      });
    }),
    http.get("*/secretstore/secrets/health", () => HttpResponse.json({ status: "UP", provider: "local", available: true })),
  );
}

function oldOperator(): OperatorConfig {
  return {
    ...defaultOperatorConfig(trimmedDefault()),
    scope: SCOPE,
    enabled: true,
    agentId: OLD_AGENT,
    version: 1,
    environment: "production",
    credentialKey: KEY,
    apiBaseUrl: "http://127.0.0.1:7070",
    provisionedRevision: 1,
    provisionedEndpoints: [...endpointsForScope(SCOPE)],
    // Trimmed to fit 6.7.0's setup-api cap, so activation recorded it as an edit.
    promptBodyIsDefault: false,
  };
}

describe("OperatorPage — the operator upgrade, end to end against a stateful backend", () => {
  let observer: MutationObserver | null = null;

  beforeEach(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
    vi.spyOn(console, "warn").mockImplementation(() => {});
    vi.spyOn(console, "info").mockImplementation(() => {});
    useOperatorChatStore.getState().reset();
    server.resetHandlers();
  });

  afterEach(() => {
    observer?.disconnect();
    observer = null;
    // Back to reading the document's own visibility, for the next file in this worker.
    focusManager.setFocused(undefined);
  });

  it("preselects Replace, carries the grant over without a prompt, and never shows a stale banner", async () => {
    const state: Backend = {
      config: oldOperator(),
      grant: [OLD_AGENT],
      oldUndeployed: false,
      oldDeleted: false,
      newDeployed: false,
      setupPrompts: [],
    };
    serveBackend(state);
    const grantPrompts: unknown[] = [];
    const unsubscribe = useGrantDialogStore.subscribe((s) => {
      if (s.pending) grantPrompts.push(s.pending);
    });

    renderWithProviders(<OperatorPage />);

    // Record every appearance of the stale banners, from the first render on: the
    // window they appear in is a few hundred milliseconds, and a point-in-time
    // query after the upgrade would miss it.
    const seen = new Set<string>();
    const STALE = ["operator-registered-undeployed", "operator-registered-missing"];
    const record = () => {
      for (const id of STALE) if (document.querySelector(`[data-testid="${id}"]`)) seen.add(id);
    };
    observer = new MutationObserver(record);
    observer.observe(document.body, { childList: true, subtree: true });

    await userEvent.click(await screen.findByTestId("operator-upgrade-start"));
    // (a) A trimmed default is replaced, not kept.
    expect(await screen.findByTestId("operator-upgrade-use-default")).toBeChecked();
    expect(screen.getByTestId("operator-upgrade-lineage")).toHaveAttribute("data-lineage", "only-removes");
    await userEvent.click(screen.getByRole("button", { name: /upgrade now/i }));

    // (d) The page lands on the NEW agent, deployed, without a reload.
    await waitFor(() => expect(state.oldDeleted).toBe(true), { timeout: 10_000 });
    await waitFor(() => expect(screen.queryByTestId("operator-upgrade-notice")).not.toBeInTheDocument(), {
      timeout: 10_000,
    });
    await waitFor(() => expect(screen.getByTestId("operator-status-ready")).toBeInTheDocument(), { timeout: 10_000 });
    // Give any late refetch of the predecessor's status the time it would take.
    await new Promise((resolve) => setTimeout(resolve, 400));
    record();
    expect([...seen], "a stale health banner was shown during or after the upgrade").toEqual([]);

    // The new default went to setup-api.
    expect(state.setupPrompts).toHaveLength(1);
    const lastDefaultLine = defaultOperatorPromptBody(SCOPE).trim().split("\n").slice(-1)[0] ?? "";
    expect(state.setupPrompts[0]).toContain(lastDefaultLine);
    expect(state.config.agentId).toBe(NEW_AGENT);
    expect(state.config.promptBodyIsDefault).toBe(true);

    // (b) The grant moved to the new agent before its deploy; nobody was asked.
    expect(state.grant).toContain(NEW_AGENT);
    expect(grantPrompts, "the grant dialog opened during the upgrade").toEqual([]);
    expect(screen.queryByTestId("grant-required-dialog")).not.toBeInTheDocument();
    expect(screen.queryByTestId("grant-required-allow-all")).not.toBeInTheDocument();
    unsubscribe();
  });
});
