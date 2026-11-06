import { test, expect, type Request } from "@playwright/test";
import { navigateTo, API_BASE } from "../fullstack/fullstack-helpers";
import { endpointsForScope } from "@/lib/operator/tool-scopes";

/**
 * Operator activation E2E — the Platform Operator activated through the
 * Manager against a real EDDI, end to end.
 *
 * 6.7.0 shipped with activation broken: the operator prompt had outgrown
 * `setup-api`'s system-prompt cap, so every activation and every upgrade
 * answered HTTP 400 — and nothing caught it, because every operator test ran
 * against MSW and none ever sent the real prompt to the real endpoint. This
 * tier exists so that whole failure class (what the Manager provisions vs. what
 * the backend accepts and runs) fails CI instead of an administrator.
 *
 * It drives the real activation code path in the shipped bundle:
 *
 *   1. fetch the deployment's OpenAPI spec and check the allow-list against it
 *      (`findMissingEndpoints` / `findUnusableEndpoints`) — a missing or
 *      unsendable endpoint fails here;
 *   2. resolve the platform base URL from `/administration/operator/self-url`;
 *   3. provision through `POST /administration/agents/setup-api` with the real
 *      prompt and allow-list, deploy, write `platform.operator`;
 *   4. read the gate back (`verifyGateInstalled`) and run the gate dry-run;
 *   5. the background probes: the read canary (one real tool call) and the
 *      write canary (a real gated write that must pause);
 *   6. a chat turn whose tool call must reach the platform and come back
 *      with data;
 *   7. and what the model was actually offered: one distinct tool per
 *      allow-listed endpoint (a repeated operationId once made assembly drop
 *      one), with optional query parameters left optional — and no deploy-time
 *      warning that the prompt does not parse as a template.
 *
 * The model is `llm-stub.mjs`, a deterministic OpenAI-compatible server, so no
 * paid provider is involved and the outcome does not depend on a model's mood.
 * The stub answers a tool result with `OPERATOR_E2E_TOOL_OK` — or
 * `OPERATOR_E2E_TOOL_FAILED` when the result is an error — so a tool that
 * cannot reach EDDI (a wrong self-url, a 401) fails the last step by name.
 *
 * Needs `docker-compose.integration-operator.yml` on top of the integration
 * compose file (see that file). `OPERATOR_E2E_LLM_BASE_URL` is the address
 * EDDI — not this browser — reaches the stub at.
 */

const LLM_BASE_URL = process.env.OPERATOR_E2E_LLM_BASE_URL ?? "http://llm-stub:8089/v1";
const MODEL = "operator-e2e-stub";
const PROBE_USER = "eddi-operator-probe";
/** Where THIS process reaches the stub's inspection endpoint (a loopback port mapping). */
const STUB_URL = process.env.OPERATOR_E2E_STUB_URL ?? "http://localhost:8089";

/** The tool name McpApiToolBuilder.generateSlug gives an operation without a usable operationId. */
function slugOf(method: string, path: string): string {
  const cleaned = path
    .replace(/[{}]/g, "")
    .replace(/[^a-zA-Z0-9/]/g, "")
    .replace(/\/+/g, "_")
    .replace(/^_|_$/g, "");
  return `${method.toLowerCase()}_${cleaned.toLowerCase()}`;
}

/** Activation plus two LLM probes plus a chat turn, on a cold JVM. */
const ACTIVATION_TIMEOUT = 180_000;

test.describe("Platform Operator activation against a real backend", () => {
  // No retries: a retry would run against the operator the first attempt
  // already activated, fail in beforeAll, and report THAT instead of the real
  // failure. The model is deterministic, so a retry has nothing to recover.
  test.describe.configure({ timeout: 360_000, retries: 0 });

  test.beforeAll(async ({ request }) => {
    // A leftover operator from an earlier local run makes the page show the
    // active operator instead of the activation form. Fail by name rather than
    // on a missing button.
    const res = await request.get(`${API_BASE}/variablestore/variables/default/platform.operator`);
    expect(
      res.status(),
      "this backend already has an operator configured (platform.operator) — run against a fresh " +
        "datastore (docker compose ... down -v) so activation starts from nothing",
    ).toBe(404);
  });

  test("activates, verifies the gate, passes both canaries and answers with a real tool call", async ({ page, request }) => {
    // The probes run in the background after activation; their traffic is
    // what proves they ran, so it is recorded from the first request on.
    const probeStarts: Request[] = [];
    const conversationEnds: Request[] = [];
    const canaryReports: Record<string, unknown>[] = [];
    // The probes explain an inconclusive or failed outcome only in the browser
    // console, so it is kept for the failure messages below.
    const consoleLines: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "warning" || msg.type() === "error") consoleLines.push(`[${msg.type()}] ${msg.text()}`);
    });
    const recentConsole = () => consoleLines.slice(-15).join("\n") || "(no console warnings or errors)";
    page.on("request", (req) => {
      const url = req.url();
      if (req.method() !== "POST") return;
      if (url.includes("/start?") && url.includes(`userId=${PROBE_USER}`)) probeStarts.push(req);
      if (url.endsWith("/endConversation")) conversationEnds.push(req);
      if (url.endsWith("/administration/operator/canary-result")) {
        try {
          canaryReports.push(JSON.parse(req.postData() ?? "{}") as Record<string, unknown>);
        } catch {
          canaryReports.push({ unparseable: req.postData() });
        }
      }
    });

    // ── The address the generated tools will call ──
    const selfUrlRes = await request.get(`${API_BASE}/administration/operator/self-url`);
    expect(selfUrlRes.ok(), "GET /administration/operator/self-url must answer").toBe(true);
    const selfUrl = (await selfUrlRes.json()) as { baseUrl?: string | null; source?: string };
    expect(selfUrl.source, "the backend could not say where it reaches itself").not.toBe("unresolved");
    expect(selfUrl.baseUrl, "self-url answered without a base URL").toBeTruthy();

    // ── Activation form ──
    await navigateTo(page, "/manage/operator");
    await page.getByTestId("empty-state").getByRole("button").click();

    await page.getByTestId("operator-provider").selectOption("openai-compatible");
    await page.getByTestId("operator-model").fill(MODEL);
    await page.getByTestId("operator-base-url").fill(LLM_BASE_URL);
    // Prefilled from self-url; asserted rather than typed, because the
    // prefill IS the behaviour under test.
    await expect(page.getByTestId("operator-platform-base-url")).toHaveValue(selfUrl.baseUrl!, { timeout: 15_000 });
    await page.getByTestId("operator-next").click();

    // read_write is the default scope, and the one with the write canary.
    await expect(page.getByTestId("operator-scope-read_write")).toBeVisible();
    await page.getByTestId("operator-scope-read_write").click();
    await page.getByTestId("operator-activate").click();

    // ── Activation outcome: READY, or the error the form shows ──
    const ready = page.getByTestId("operator-status-ready");
    const failed = page.getByTestId("operator-activation-error");
    await expect(ready.or(failed).first()).toBeVisible({ timeout: ACTIVATION_TIMEOUT });
    if (await failed.isVisible()) {
      throw new Error(`Operator activation failed: ${await failed.innerText()}`);
    }
    await expect(page.getByTestId("operator-gate-verified"), "the approval gate was not verified").toBeVisible({
      timeout: 60_000,
    });

    // ── What activation recorded ──
    const configRes = await request.get(`${API_BASE}/variablestore/variables/default/platform.operator`);
    expect(configRes.ok(), "activation did not record platform.operator").toBe(true);
    const stored = (await configRes.json()) as { value?: unknown };
    const config = JSON.parse(typeof stored.value === "string" ? stored.value : JSON.stringify(stored.value)) as {
      enabled?: boolean;
      agentId?: string;
      apiBaseUrl?: string;
      scope?: string;
    };
    expect(config.enabled).toBe(true);
    expect(config.scope).toBe("read_write");
    expect(config.agentId).toBeTruthy();
    expect(config.apiBaseUrl, "the operator was provisioned against an address other than self-url").toBe(
      selfUrl.baseUrl,
    );

    // ── The background probes: read canary + write canary ──
    // Both start a conversation as the probe user and end it when done.
    await expect
      .poll(() => probeStarts.length, { message: "the read and write probes never started", timeout: 60_000 })
      .toBeGreaterThanOrEqual(2);
    await expect
      .poll(() => canaryReports.length, { message: "the write canary never reported an outcome", timeout: ACTIVATION_TIMEOUT })
      .toBeGreaterThanOrEqual(1);
    expect(
      canaryReports[0]?.outcome,
      `write canary outcome: ${JSON.stringify(canaryReports[0])}\nbrowser console:\n${recentConsole()}`,
    ).toBe("pass");
    await expect
      .poll(() => conversationEnds.length, { message: "the probes did not end their conversations", timeout: 60_000 })
      .toBeGreaterThanOrEqual(2);
    // The read canary reports only by failing: a warning on the page.
    const canaryWarning = page.getByTestId("operator-canary-warning");
    await expect
      .poll(async () => ((await canaryWarning.count()) > 0 ? await canaryWarning.innerText() : ""), {
        message: `the read canary failed — its warning is the received value\nbrowser console:\n${recentConsole()}`,
        timeout: 5_000,
      })
      .toBe("");
    // A proven gate breach tears the operator down and lands here.
    await expect(failed).toHaveCount(0);
    await expect(ready).toBeVisible();

    // ── A real chat turn with a real tool call ──
    await page.getByTestId("operator-input").fill("OPERATOR_E2E: list the agents on this platform.");
    await page.getByTestId("operator-send").click();
    const transcript = page.getByTestId("operator-messages");
    await expect(transcript.getByText(/OPERATOR_E2E_TOOL_(OK|FAILED)/).first()).toBeVisible({
      timeout: 90_000,
    });
    const text = await transcript.innerText();
    expect(text, "the operator's tool call reached the platform but failed").not.toContain("OPERATOR_E2E_TOOL_FAILED");
    expect(text).toContain("OPERATOR_E2E_TOOL_OK");
    await expect(page.getByTestId("operator-chat-error")).toHaveCount(0);

    // ── What the model was offered ──
    const offeredRes = await request.get(`${STUB_URL}/__e2e/tools`);
    expect(offeredRes.ok(), `the model stub's inspection endpoint (${STUB_URL}) did not answer`).toBe(true);
    const offered = (await offeredRes.json()) as { tools: { name: string; required: string[] }[] };
    const offeredNames = new Set(offered.tools.map((tool) => tool.name));
    const specRes = await request.get(`${API_BASE}/openapi?format=json`);
    expect(specRes.ok()).toBe(true);
    const spec = (await specRes.json()) as { paths: Record<string, Record<string, { operationId?: string }>> };
    // Every allow-listed endpoint the spec has must have reached the model as
    // its OWN tool: named by its operationId, or by its method-and-path slug
    // when an earlier operation already took that id. Each offered name is
    // claimed once, so two endpoints sharing one tool count as one missing.
    const claimed = new Set<string>();
    const undelivered: string[] = [];
    for (const endpoint of endpointsForScope("read_write")) {
      const [method, path] = endpoint.split(" ") as [string, string];
      const operation = spec.paths[path]?.[method.toLowerCase()];
      if (!operation) continue; // findMissingEndpoints already refused this case
      const candidates = [operation.operationId, slugOf(method, path)].filter(
        (name): name is string => !!name && offeredNames.has(name) && !claimed.has(name),
      );
      if (candidates.length === 0) undelivered.push(`${endpoint} (${operation.operationId ?? "no operationId"})`);
      else claimed.add(candidates[0]!);
    }
    expect(undelivered, "allow-listed endpoints that never reached the model as a tool of their own").toEqual([]);
    // An optional query parameter is optional: the model is not made to invent
    // a filter, a page index and a page size to list agents.
    const listAgents = offered.tools.find((tool) => tool.name === "readAgentDescriptors");
    expect(listAgents, "readAgentDescriptors was not offered").toBeTruthy();
    expect(listAgents!.required, "readAgentDescriptors requires its optional query parameters").toEqual([]);

    // ── What the deploy-time compatibility check said ──
    // It parses every template with the runtime engine; a prompt Qute cannot
    // parse fails its render on every turn and is reported here.
    const statusRes = await request.get(`${API_BASE}/administration/production/deploymentstatus`);
    expect(statusRes.ok()).toBe(true);
    const statuses = (await statusRes.json()) as { agentId?: string; warnings?: string[] | null }[];
    const operatorStatus = statuses.find((row) => row.agentId === config.agentId);
    expect(operatorStatus, "the operator is not in the production deployment status").toBeTruthy();
    expect(
      (operatorStatus!.warnings ?? []).filter((warning) => warning.includes("TEMPLATE_DOES_NOT_PARSE")),
      "the deploy-time compatibility check found a template that does not parse",
    ).toEqual([]);
  });
});
