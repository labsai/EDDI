import { test, expect, type APIRequestContext, type Page, type Request } from "@playwright/test";
import { API_BASE, KEYCLOAK_BASE, USERS, authHeaders, tokenFor, waitForBackend } from "../auth/auth-helpers";

/**
 * Operator activation under Keycloak, with the operator's tool calls running as
 * the signed-in user (`authMode: "caller-identity"`, `${caller:token}`).
 *
 * Every other operator tier runs EDDI with EDDI_SECURITY_ALLOW_UNAUTHENTICATED,
 * where a tool call that carries no credential still succeeds — so nothing there
 * can tell a working caller-identity from a missing one. Here OIDC is enforced:
 * a tool call without the caller's token is refused before it reaches an
 * endpoint, and one that carries it is recorded under that user's name.
 *
 * The administrator signs in through Keycloak's own login page, activates the
 * operator (read_write, the stub model, caller-identity) and the spec asserts:
 *
 *   1. activation succeeds — gate verified, both canaries pass;
 *   2. a read tool call answers through the stub (it would 401 without a token);
 *   3. a gated write the administrator approves in the operator chat executes,
 *      and the backend's admin-action audit records it under the SIGNED-IN
 *      USER's principal — the same actor the user's own REST call is recorded
 *      under — not `anonymous` and not a service identity.
 *
 * Stack: docker-compose.integration-keycloak.yml with
 * docker-compose.integration-operator.yml layered on it (the model stub and a
 * vault key), the realm from `npm run realm:test`. CI runs it in the
 * `Auth E2E (Keycloak)` job, after the auth tier, on the same stack.
 */

const LLM_BASE_URL = process.env.OPERATOR_E2E_LLM_BASE_URL ?? "http://llm-stub:8089/v1";
const MODEL = "operator-e2e-stub";
const PROBE_USER = "eddi-operator-probe";
const ACTIVATION_TIMEOUT = 180_000;
const DESCRIPTOR_PATCH = { method: "patch", path: "/descriptorstore/descriptors/{id}" };

/** Sign in through Keycloak's login form, as a person would. */
async function signIn(page: Page, path: string) {
  await page.goto(path);
  await page.waitForURL((url) => url.href.startsWith(KEYCLOAK_BASE), { timeout: 30_000 });
  await page.locator("#username").fill(USERS.admin.username);
  await page.locator("#password").fill(USERS.admin.password);
  await page.locator("#kc-login").click();
  await page.waitForURL((url) => url.href.startsWith(API_BASE), { timeout: 30_000 });
  await page.getByTestId("app-layout").waitFor({ timeout: 30_000 });
}

type AdminAction = { userId: string; input?: { method?: string; path?: string }; output?: { status?: number } };

async function adminActions(request: APIRequestContext, token: string): Promise<AdminAction[]> {
  const res = await request.get(`${API_BASE}/auditstore/admin-actions?limit=200`, { headers: authHeaders(token) });
  expect(res.ok(), `GET /auditstore/admin-actions answered ${res.status()}`).toBe(true);
  return (await res.json()) as AdminAction[];
}

test.describe("Platform Operator under Keycloak, calling as the signed-in user", () => {
  // No retries, for the reason the activation tier gives: a retry would run
  // against the operator the first attempt activated and report that instead.
  test.describe.configure({ timeout: 480_000, retries: 0 });

  let token: string;

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
    token = await tokenFor(request, "admin");
    const res = await request.get(`${API_BASE}/variablestore/variables/default/platform.operator`, {
      headers: authHeaders(token),
    });
    expect(
      res.status(),
      "this backend already has an operator configured — run against a fresh datastore (docker compose ... down -v)",
    ).toBe(404);
  });

  test("activates with caller identity, and its tool calls run as the signed-in user", async ({ page, request }) => {
    const probeStarts: Request[] = [];
    const canaryReports: Record<string, unknown>[] = [];
    const consoleLines: string[] = [];
    page.on("console", (msg) => {
      if (msg.type() === "warning" || msg.type() === "error") consoleLines.push(`[${msg.type()}] ${msg.text()}`);
    });
    const recentConsole = () => consoleLines.slice(-15).join("\n") || "(no console warnings or errors)";
    page.on("request", (req) => {
      if (req.method() !== "POST") return;
      const url = req.url();
      if (url.includes("/start?") && url.includes(`userId=${PROBE_USER}`)) probeStarts.push(req);
      if (url.endsWith("/administration/operator/canary-result")) {
        try {
          canaryReports.push(JSON.parse(req.postData() ?? "{}") as Record<string, unknown>);
        } catch {
          canaryReports.push({ unparseable: req.postData() });
        }
      }
    });

    // ── The signed-in user's own actor name, as the audit records it ──
    // A bystander agent created over REST with the user's token: the record of
    // that POST is what "recorded as the signed-in user" means on this backend.
    const created = await request.post(`${API_BASE}/agentstore/agents`, {
      headers: authHeaders(token),
      data: { workflows: [] },
    });
    expect(created.status(), `could not create the bystander agent: ${await created.text()}`).toBe(201);
    const bystander = /\/agents\/([0-9a-f]+)/i.exec(created.headers()["location"] ?? "")?.[1];
    expect(bystander).toBeTruthy();
    // The ledger is written asynchronously, so its records are polled for.
    let ownRecord: AdminAction | undefined;
    await expect
      .poll(
        async () =>
          (ownRecord = (await adminActions(request, token)).find(
            (a) => a.input?.method === "POST" && a.input?.path === "/agentstore/agents",
          )),
        { message: "the user's own POST /agentstore/agents was not audited", timeout: 30_000 },
      )
      .toBeTruthy();
    const actor = ownRecord!.userId;
    expect(actor, "the user's own call was audited without the user's name").toBe(USERS.admin.username);

    // ── Activation, through the bundle this image serves, as the signed-in user ──
    // Absolute: this tier needs no PORT, and the config's baseURL defaults to the dev server's.
    await signIn(page, `${API_BASE}/manage/operator`);
    await page.getByTestId("empty-state").getByRole("button").click();
    await page.getByTestId("operator-provider").selectOption("openai-compatible");
    await page.getByTestId("operator-model").fill(MODEL);
    await page.getByTestId("operator-base-url").fill(LLM_BASE_URL);
    await page.getByTestId("operator-auth-caller-identity").check();
    await page.getByTestId("operator-next").click();
    await page.getByTestId("operator-scope-read_write").check();
    await page.getByTestId("operator-activate").click();

    const ready = page.getByTestId("operator-status-ready");
    const failed = page.getByTestId("operator-activation-error");
    await expect(ready.or(failed).first()).toBeVisible({ timeout: ACTIVATION_TIMEOUT });
    if (await failed.isVisible()) throw new Error(`Operator activation failed: ${await failed.innerText()}`);
    await expect(page.getByTestId("operator-gate-verified"), "the approval gate was not verified").toBeVisible({
      timeout: 60_000,
    });

    const configRes = await request.get(`${API_BASE}/variablestore/variables/default/platform.operator`, {
      headers: authHeaders(token),
    });
    expect(configRes.ok()).toBe(true);
    const config = JSON.parse(((await configRes.json()) as { value: string }).value) as {
      agentId: string;
      authMode?: string;
      scope?: string;
    };
    expect(config.authMode).toBe("caller-identity");
    expect(config.scope).toBe("read_write");

    // ── Both canaries ──
    await expect
      .poll(() => probeStarts.length, { message: "the read and write probes never started", timeout: 60_000 })
      .toBeGreaterThanOrEqual(2);
    await expect
      .poll(() => canaryReports.length, { message: "the write canary never reported", timeout: ACTIVATION_TIMEOUT })
      .toBeGreaterThanOrEqual(1);
    expect(
      canaryReports[0]?.outcome,
      `write canary outcome: ${JSON.stringify(canaryReports[0])}\nbrowser console:\n${recentConsole()}`,
    ).toBe("pass");
    const canaryWarning = page.getByTestId("operator-canary-warning");
    await expect
      .poll(async () => ((await canaryWarning.count()) > 0 ? await canaryWarning.innerText() : ""), {
        message: `the read canary failed — its warning is the received value\nbrowser console:\n${recentConsole()}`,
        timeout: 30_000,
      })
      .toBe("");
    await expect(failed).toHaveCount(0);

    // ── A read tool call: refused without the caller's token, answered with it ──
    const transcript = page.getByTestId("operator-messages");
    await page.getByTestId("operator-input").fill("OPERATOR_E2E: list the agents on this platform.");
    await page.getByTestId("operator-send").click();
    await expect(transcript.getByText(/OPERATOR_E2E_TOOL_(OK|FAILED)/).first()).toBeVisible({ timeout: 90_000 });
    expect(await transcript.innerText(), "the read tool call was refused — no caller token?").not.toContain(
      "OPERATOR_E2E_TOOL_FAILED",
    );
    await expect(page.getByTestId("operator-chat-error")).toHaveCount(0);

    // ── A gated write, approved in the chat, recorded as the signed-in user ──
    const specRes = await request.get(`${API_BASE}/openapi?format=json`, { headers: authHeaders(token) });
    expect(specRes.ok()).toBe(true);
    const spec = (await specRes.json()) as { paths: Record<string, Record<string, { operationId?: string }>> };
    const patchTool = spec.paths[DESCRIPTOR_PATCH.path]?.[DESCRIPTOR_PATCH.method]?.operationId;
    expect(patchTool, "the descriptor PATCH has no operationId in the spec").toBeTruthy();

    const okBefore = await transcript.getByText("OPERATOR_E2E_TOOL_OK").count();
    // The stub turns exactly this sentence into that one tool call (see llm-stub.mjs).
    await page.getByTestId("operator-input").fill(`Call \`${patchTool}\` with id \`${bystander}\` to rename it.`);
    await page.getByTestId("operator-send").click();
    const banner = page.getByTestId("approval-banner");
    await expect(banner, "the gated write did not pause for approval").toBeVisible({ timeout: 90_000 });
    // A batch can ask for a decision per call before the banner's own approve.
    for (const perCall of await banner.locator('[data-testid^="tool-approve-"]').all()) {
      if (await perCall.isEnabled()) await perCall.click();
    }
    await banner.getByTestId("approve-button").click();
    // "Approve tool execution? … cannot be undone." — the second, explicit yes.
    await page.getByTestId("alert-dialog-confirm").click();
    await expect
      .poll(() => transcript.getByText("OPERATOR_E2E_TOOL_OK").count(), {
        message: "the approved write did not come back with a successful result",
        timeout: 90_000,
      })
      .toBeGreaterThan(okBefore);
    expect(await transcript.innerText(), "the approved write was refused").not.toContain("OPERATOR_E2E_TOOL_FAILED");

    let recorded: AdminAction[] = [];
    await expect
      .poll(
        async () =>
          (recorded = (await adminActions(request, token)).filter(
            (a) => a.input?.method === "PATCH" && a.input?.path === `/descriptorstore/descriptors/${bystander}`,
          )).length,
        { message: "the operator's write is not in the admin-action audit", timeout: 30_000 },
      )
      .toBe(1);
    expect(recorded[0]!.userId, "the operator's write ran as someone other than the signed-in user").toBe(actor);
    expect(recorded[0]!.output?.status, "the operator's write was refused by the endpoint").toBeLessThan(300);
  });
});
