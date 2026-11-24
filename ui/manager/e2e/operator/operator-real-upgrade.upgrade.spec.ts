import { test, expect, type APIRequestContext, type Browser, type BrowserContext, type Page } from "@playwright/test";
import { spawnSync } from "node:child_process";
import { readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { navigateTo, API_BASE, waitForBackend } from "../fullstack/fullstack-helpers";

/**
 * Operator REAL-upgrade E2E — the Platform Operator activated by the previous
 * EDDI release, then upgraded by the image under test, the way an administrator
 * actually upgrades a deployment.
 *
 * `operator-activation.operator.spec.ts` covers an upgrade too, but a synthetic
 * one: it rewrites a current operator's stored config to claim an older
 * revision. That cannot reproduce what a real older operator leaves behind —
 * the older Manager's stored fields, the older tool shapes and gate baked into
 * the agent, the older grant prompt's result in the vault. This spec makes the
 * real one:
 *
 *   1. start the PREVIOUS release's image (`previous-release.json`) on a fresh
 *      MongoDB volume, beside the model stub;
 *   2. activate the operator through THAT release's own Manager bundle
 *      (read_write, the stub model, a vault-held key restricted to another
 *      agent, so its own grant prompt runs), and see it answer one tool call;
 *   3. stop that container and start the image under test on the same volume;
 *   4. upgrade through the current Manager and assert: the banner appears; the
 *      instructions choice is preselected correctly; no grant prompt and no
 *      "allow every agent"; the new agent is deployed with no stale banner and
 *      no reload; the deleted agent is gone from the grant (and the bystander
 *      agent is not); a real tool call answers through the stub.
 *
 * It runs twice: once with the default instructions untouched (replaced, no
 * choice offered) and once with a line of the administrator's own added in the
 * old Manager (Keep preselected, and the line reaches the model afterwards).
 *
 * The spec drives the compose stack itself, because the container swap is in
 * the middle of it: `docker compose` with the integration, operator and
 * operator-upgrade files under a project of its own, torn down with `down -v`.
 * `EDDI_IMAGE` must name the image under test (CI: labsai/eddi:ci).
 */

const MANAGER_DIR = fileURLToPath(new URL("../..", import.meta.url));
const COMPOSE_FILES = [
  "docker-compose.integration.yml",
  "docker-compose.integration-operator.yml",
  "docker-compose.integration-operator-upgrade.yml",
];
const PROJECT = process.env.OPERATOR_UPGRADE_PROJECT ?? "eddi-operator-upgrade";

const PREVIOUS_IMAGE =
  process.env.OPERATOR_UPGRADE_FROM_IMAGE ??
  (JSON.parse(readFileSync(new URL("./previous-release.json", import.meta.url), "utf8")) as { image: string }).image;
const IMAGE_UNDER_TEST = process.env.EDDI_IMAGE ?? "";

const OPERATOR_REVISION = (
  JSON.parse(readFileSync(new URL("../../src/lib/operator/operator-revision.json", import.meta.url), "utf8")) as {
    revision: number;
  }
).revision;

const LLM_BASE_URL = process.env.OPERATOR_E2E_LLM_BASE_URL ?? "http://llm-stub:8089/v1";
const STUB_URL = process.env.OPERATOR_E2E_STUB_URL ?? "http://localhost:8089";
const MODEL = "operator-e2e-stub";
const KEY = "operator-e2e-model-key";
const VAR = `${API_BASE}/variablestore/variables/default/platform.operator`;
const SECRET = `${API_BASE}/secretstore/secrets/default/${KEY}`;
/** The administrator's own line, added in the old Manager. No braces: the prompt is a Qute template. */
const ADMIN_LINE = "Site rule added by the administrator: OPERATOR_E2E_ADMIN_LINE keep answers short.";

/** Activation (old or new) plus its background probes, on a cold JVM. */
const ACTIVATION_TIMEOUT = 180_000;

type StoredOperator = {
  agentId: string;
  provisionedRevision?: number;
  promptBody?: string;
  promptBodyIsDefault?: boolean;
  credentialKey?: string;
  scope?: string;
};

// ── The compose stack ──────────────────────────────────────────────────────

/** Run `docker compose` against this spec's project, with EDDI_IMAGE set to `image`. */
function compose(args: string[], image: string): string {
  const res = spawnSync("docker", ["compose", "-p", PROJECT, ...COMPOSE_FILES.flatMap((f) => ["-f", f]), ...args], {
    cwd: MANAGER_DIR,
    env: { ...process.env, EDDI_IMAGE: image },
    encoding: "utf8",
    timeout: 480_000,
  });
  if (res.status !== 0) {
    throw new Error(`docker compose ${args.join(" ")} failed (${res.status ?? res.signal}):\n${res.stdout}\n${res.stderr}`);
  }
  return res.stdout;
}

/** The image the running `eddi` container was created from. */
function runningEddiImage(): string {
  const id = compose(["ps", "-q", "eddi"], IMAGE_UNDER_TEST).trim();
  const res = spawnSync("docker", ["inspect", "--format", "{{.Config.Image}}", id], { encoding: "utf8" });
  return res.stdout.trim();
}

/** Keep the stack's logs beside the report, then remove it with its volumes. */
function tearDown(label: string) {
  try {
    const logs = compose(["logs", "--no-color"], IMAGE_UNDER_TEST);
    const dir = `${MANAGER_DIR}/test-results`;
    mkdirSync(dir, { recursive: true });
    writeFileSync(`${dir}/operator-upgrade-${label}-logs.txt`, logs);
  } catch {
    // Logs are a convenience; the teardown below is not.
  }
  if (process.env.OPERATOR_UPGRADE_KEEP !== "1") compose(["down", "-v"], IMAGE_UNDER_TEST);
}

/** A fresh stack on the previous release: new MongoDB volume, new stub. */
async function startPreviousRelease(request: APIRequestContext) {
  compose(["down", "-v"], PREVIOUS_IMAGE);
  compose(["up", "-d", "--wait"], PREVIOUS_IMAGE);
  await waitForBackend(request, 180_000);
}

/** Stop the previous release's container and start the image under test on the same volume. */
async function upgradeContainer(request: APIRequestContext) {
  compose(["stop", "eddi"], PREVIOUS_IMAGE);
  compose(["rm", "-f", "eddi"], PREVIOUS_IMAGE);
  compose(["up", "-d", "--wait", "--no-deps", "eddi"], IMAGE_UNDER_TEST);
  await waitForBackend(request, 180_000);
  expect(runningEddiImage(), "the eddi container was not recreated from the image under test").toBe(IMAGE_UNDER_TEST);
}

// ── Backend reads ──────────────────────────────────────────────────────────

async function readOperator(request: APIRequestContext): Promise<StoredOperator> {
  const res = await request.get(VAR);
  expect(res.ok(), `platform.operator is not readable (${res.status()})`).toBe(true);
  const variable = (await res.json()) as { value: unknown };
  return JSON.parse(typeof variable.value === "string" ? variable.value : JSON.stringify(variable.value)) as StoredOperator;
}

async function allowedAgents(request: APIRequestContext): Promise<string[]> {
  const res = await request.get(SECRET);
  expect(res.ok(), `the vault secret is not readable (${res.status()})`).toBe(true);
  return ((await res.json()) as { allowedAgents: string[] }).allowedAgents;
}

/** An agent that is not the operator, so the grant is restricted to something real. */
async function createBystanderAgent(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${API_BASE}/agentstore/agents`, { data: { workflows: [] } });
  expect(res.status(), `could not create the bystander agent: ${await res.text()}`).toBe(201);
  const location = res.headers()["location"] ?? "";
  const id = /\/agents\/([0-9a-f]+)/i.exec(location)?.[1];
  expect(id, `no agent id in Location: ${location}`).toBeTruthy();
  return id!;
}

// ── The browser ────────────────────────────────────────────────────────────

/**
 * A browser context of its own per release: the two Managers are different
 * bundles on the same origin, and nothing of the old one's session (its query
 * cache, its in-memory probe state) may carry into the new one.
 */
async function freshPage(browser: Browser): Promise<{ context: BrowserContext; page: Page }> {
  const use = test.info().project.use;
  const context = await browser.newContext({ baseURL: use.baseURL, storageState: use.storageState });
  return { context, page: await context.newPage() };
}

/** One chat turn that must make a real tool call and get data back through the stub. */
async function assertToolCallAnswers(page: Page, label: string) {
  await page.getByTestId("operator-input").fill("OPERATOR_E2E: list the agents on this platform.");
  await page.getByTestId("operator-send").click();
  const transcript = page.getByTestId("operator-messages");
  await expect(transcript.getByText(/OPERATOR_E2E_TOOL_(OK|FAILED)/).first(), `${label}: no tool result came back`).toBeVisible({
    timeout: 90_000,
  });
  const text = await transcript.innerText();
  expect(text, `${label}: the operator's tool call reached the platform but failed`).not.toContain("OPERATOR_E2E_TOOL_FAILED");
}

/**
 * Activate the operator through the PREVIOUS release's Manager.
 *
 * Written against that release's UI as it actually renders (checked by running
 * it), not against the current source: the steps that might differ — the grant
 * prompt for a restricted key — are waited for rather than assumed.
 */
async function activateOnPreviousRelease(page: Page, request: APIRequestContext, edited: boolean) {
  const selfUrl = (await (await request.get(`${API_BASE}/administration/operator/self-url`)).json()) as {
    baseUrl?: string;
  };

  await navigateTo(page, "/manage/operator");
  await page.getByTestId("empty-state").getByRole("button").click();
  await page.getByTestId("operator-provider").selectOption("openai-compatible");
  await page.getByTestId("operator-model").fill(MODEL);
  await page.getByTestId("operator-base-url").fill(LLM_BASE_URL);
  // The key picker accepts a typed vault reference and then shows the key as picked.
  await page.getByTestId("operator-api-key-input").fill(`\${vault:${KEY}}`);
  await expect(page.getByTestId("operator-key-restricted"), "the old Manager did not see the key as restricted").toBeVisible();
  await expect(page.getByTestId("operator-platform-base-url")).toHaveValue(selfUrl.baseUrl ?? "", { timeout: 15_000 });
  await page.getByTestId("operator-next").click();

  await page.getByTestId("operator-scope-read_write").check();
  if (edited) {
    const body = page.getByTestId("operator-prompt-body");
    const current = await body.inputValue();
    expect(current.length, "the old Manager did not prefill the default instructions").toBeGreaterThan(1000);
    await body.fill(`${current}\n${ADMIN_LINE}`);
  }
  await page.getByTestId("operator-activate").click();

  // The old release asks to add the new agent to the restricted key's grant.
  const ready = page.getByTestId("operator-status-ready");
  const failed = page.getByTestId("operator-activation-error");
  const grantConfirm = page.getByTestId("grant-required-confirm");
  await expect(ready.or(failed).or(grantConfirm).first()).toBeVisible({ timeout: ACTIVATION_TIMEOUT });
  if (await grantConfirm.isVisible()) {
    await expect(page.getByTestId("grant-required-allow-all")).not.toBeChecked();
    await grantConfirm.click();
    await expect(ready.or(failed).first()).toBeVisible({ timeout: ACTIVATION_TIMEOUT });
  }
  if (await failed.isVisible()) throw new Error(`Activation on ${PREVIOUS_IMAGE} failed: ${await failed.innerText()}`);
  await expect(page.getByTestId("operator-gate-verified")).toBeVisible({ timeout: 60_000 });

  await assertToolCallAnswers(page, `the ${PREVIOUS_IMAGE} operator`);
}

/** Record every appearance of the given test ids from now on, so a banner that flashed is not missed. */
async function watchFor(page: Page, ids: string[]) {
  await page.evaluate((watched) => {
    const seen = new Set<string>();
    const record = () => {
      for (const id of watched) if (document.querySelector(`[data-testid="${id}"]`)) seen.add(id);
    };
    new MutationObserver(record).observe(document.body, { childList: true, subtree: true, attributes: true });
    record();
    (window as unknown as { __operatorUpgradeSeen: Set<string> }).__operatorUpgradeSeen = seen;
  }, ids);
}

/** What `watchFor` recorded, or `null` when the document it was installed in has been replaced (a reload). */
async function seenSoFar(page: Page): Promise<string[] | null> {
  return page.evaluate(() => {
    const seen = (window as unknown as { __operatorUpgradeSeen?: Set<string> }).__operatorUpgradeSeen;
    return seen ? [...seen] : null;
  });
}

// ── The test ───────────────────────────────────────────────────────────────

test.describe("Platform Operator upgraded from the previous release", () => {
  // No retries: each run starts from a fresh volume, and a deterministic model
  // leaves a retry nothing to recover — it would only hide a flake's first report.
  test.describe.configure({ mode: "serial", timeout: 900_000, retries: 0 });

  test.beforeAll(() => {
    expect(
      IMAGE_UNDER_TEST,
      "EDDI_IMAGE must name the image under test (CI: labsai/eddi:ci); without it the 'upgrade' would start the published image again",
    ).toBeTruthy();
    expect(IMAGE_UNDER_TEST, "the image under test is the previous release itself").not.toBe(PREVIOUS_IMAGE);
  });

  for (const edited of [false, true]) {
    const label = edited ? "edited" : "default";
    test(`${label} instructions: activated on the previous release, upgraded on this image`, async ({ browser, request }) => {
      try {
        // ── 1. The previous release ──
        await startPreviousRelease(request);
        expect(runningEddiImage()).toBe(PREVIOUS_IMAGE);

        const bystander = await createBystanderAgent(request);
        const created = await request.put(SECRET, {
          data: {
            value: "fixturefixturefixture",
            description: "operator upgrade E2E",
            allowedAgents: [bystander],
            allowedHosts: ["llm-stub:8089", "llm-stub"],
          },
        });
        expect(created.ok(), `could not create the vault secret: ${created.status()} ${await created.text()}`).toBe(true);

        // ── 2. Activation through that release's own Manager ──
        const old = await freshPage(browser);
        await activateOnPreviousRelease(old.page, request, edited);
        await old.context.close();

        const before = await readOperator(request);
        const oldAgentId = before.agentId;
        expect(oldAgentId).toBeTruthy();
        expect(before.scope).toBe("read_write");
        expect(before.credentialKey, "the old Manager did not record the vault key").toBe(KEY);
        expect(before.promptBodyIsDefault, "the old Manager recorded the wrong instructions provenance").toBe(!edited);
        expect(await allowedAgents(request), "the old Manager's grant prompt did not add its agent").toEqual(
          expect.arrayContaining([bystander, oldAgentId]),
        );

        // ── 3. The container swap ──
        await upgradeContainer(request);

        // The previous release normally provisions an older revision. Right
        // after a release whose operator matches this one, it does not, and the
        // banner would have nothing to offer: say so, and lower only the
        // revision number — every other stored field stays the old Manager's.
        if ((before.provisionedRevision ?? 0) >= OPERATOR_REVISION) {
          test.info().annotations.push({
            type: "same-revision",
            description: `${PREVIOUS_IMAGE} provisioned revision ${before.provisionedRevision}, this Manager ${OPERATOR_REVISION}: the stored revision was lowered by one`,
          });
          const variable = (await (await request.get(VAR)).json()) as Record<string, unknown>;
          const res = await request.put(VAR, {
            data: { ...variable, value: JSON.stringify({ ...before, provisionedRevision: OPERATOR_REVISION - 1 }) },
          });
          expect(res.ok()).toBe(true);
        }

        // ── 4. The upgrade through the current Manager ──
        const current = await freshPage(browser);
        const page = current.page;
        const canaryReports: Record<string, unknown>[] = [];
        page.on("request", (req) => {
          if (req.method() === "POST" && req.url().endsWith("/administration/operator/canary-result")) {
            try {
              canaryReports.push(JSON.parse(req.postData() ?? "{}") as Record<string, unknown>);
            } catch {
              canaryReports.push({ unparseable: req.postData() });
            }
          }
        });
        await navigateTo(page, "/manage/operator");
        await expect(page.getByTestId("operator-upgrade-notice"), "no upgrade banner for the older operator").toBeVisible({
          timeout: 30_000,
        });
        // Installed in this document: a reload replaces the document, and the record with it.
        await watchFor(page, [
          "operator-registered-undeployed",
          "operator-registered-missing",
          "grant-required-dialog",
          "grant-required-allow-all",
        ]);

        await page.getByTestId("operator-upgrade-start").click();
        if (edited) {
          // A line found in no shipped default is the administrator's: Keep.
          await expect(page.getByTestId("operator-upgrade-lineage")).toHaveAttribute("data-lineage", "edited");
          await expect(page.getByTestId("operator-upgrade-keep-current")).toBeChecked();
          await expect(page.getByTestId("operator-upgrade-use-default")).not.toBeChecked();
          // Keeping stored text means reading it first; the added line is in the diff.
          await expect(page.getByTestId("operator-upgrade-diff").locator('[data-diff="added"]', { hasText: ADMIN_LINE })).toHaveCount(1);
          await page.getByTestId("operator-upgrade-review-confirm").check();
        } else {
          // An untouched default is simply replaced: no choice is offered at all.
          await expect(page.getByTestId("operator-upgrade-instructions")).toHaveCount(0);
        }
        await page.getByRole("button", { name: /upgrade now/i }).click();

        const errorBox = page.getByTestId("operator-upgrade-error");
        await expect
          .poll(
            async () => {
              if (await errorBox.isVisible()) return `failed: ${await errorBox.innerText()}`;
              return (await page.getByTestId("operator-upgrade-notice").count()) === 0 ? "upgraded" : "running";
            },
            { message: "the upgrade did not finish", timeout: ACTIVATION_TIMEOUT },
          )
          .toBe("upgraded");
        await expect(page.getByTestId("operator-status-ready")).toBeVisible({ timeout: 60_000 });
        await expect(page.getByTestId("operator-gate-verified")).toBeVisible({ timeout: 60_000 });

        // ── What the upgrade recorded ──
        const after = await readOperator(request);
        expect(after.agentId, "the upgrade did not replace the agent").not.toBe(oldAgentId);
        expect(after.provisionedRevision).toBe(OPERATOR_REVISION);
        expect(after.credentialKey, "the upgrade lost the vault key").toBe(KEY);
        expect(after.promptBodyIsDefault).toBe(!edited);
        if (edited) expect(after.promptBody, "the administrator's line did not survive the upgrade").toContain(ADMIN_LINE);
        else expect(after.promptBody ?? "").not.toContain(ADMIN_LINE);

        // The write canary of the upgraded operator ran and passed.
        await expect
          .poll(() => canaryReports.length, { message: "the upgraded operator's write canary never reported", timeout: ACTIVATION_TIMEOUT })
          .toBeGreaterThanOrEqual(1);
        expect(canaryReports.at(-1)?.outcome, `write canary: ${JSON.stringify(canaryReports.at(-1))}`).toBe("pass");
        await expect(page.getByTestId("operator-canary-warning")).toHaveCount(0);

        // ── A real tool call through the upgraded operator ──
        await assertToolCallAnswers(page, "the upgraded operator");
        const prompt = (await (await request.get(`${STUB_URL}/__e2e/system-prompt?contains=${encodeURIComponent(ADMIN_LINE)}`)).json()) as {
          contains: boolean;
          length: number;
        };
        expect(prompt.length, "the stub saw no system prompt").toBeGreaterThan(0);
        expect(prompt.contains, edited ? "the kept line never reached the model" : "a line of the old text reached the model").toBe(edited);

        // ── No prompt, no stale banner, no reload ──
        const seen = await seenSoFar(page);
        expect(seen, "the page reloaded during the upgrade (the watcher installed before it is gone)").not.toBeNull();
        expect(seen!.filter((id) => id.startsWith("grant-required")), "the upgrade asked for the vault grant").toEqual([]);
        expect(seen!.filter((id) => id.startsWith("operator-registered")), "a stale operator banner was shown").toEqual([]);

        // ── The grant follows the operator; the bystander keeps its place ──
        const grant = await allowedAgents(request);
        expect(grant, "the new operator agent was not added to the grant").toContain(after.agentId);
        expect(grant, "the deleted operator agent is still on the grant").not.toContain(oldAgentId);
        expect(grant, "the upgrade removed an unrelated agent from the grant").toContain(bystander);
        const gone = await request.get(`${API_BASE}/agentstore/agents/${oldAgentId}/currentversion`);
        expect(gone.status(), "the old operator agent was not deleted by the upgrade").toBe(404);

        await current.context.close();
      } finally {
        tearDown(label);
      }
    });
  }
});
