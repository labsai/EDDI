import { test, expect, type APIRequestContext, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { API_BASE, waitForBackend } from "./fullstack-helpers";
import { STILL_LOADING } from "../e2e-helpers";
import { cleanupResource, createAndDeployAgent } from "../integration/integration-helpers";

/**
 * Brand-leak acceptance test (white-label plan §7, phase 7).
 *
 * Sets a test brand through the admin REST API, walks every top-level route of
 * `/`, `/welcome`, `/manage`, `/workforce` and `/chat` against the real server,
 * and reports every place the EDDI brand still shows: visible text, the
 * document title, accessibility and hint attributes, the server-rendered
 * `<head>` of the raw HTML, image sources and the primary colour. Then it
 * resets the brand and checks that representative pages say EDDI again.
 *
 * It needs the real server — `/branding/*`, the head substitution and the
 * admin REST are all server-side, so MSW mode cannot prove any of them. It runs
 * in the `fullstack` project (CI: Backend E2E, `npm run test:e2e:fullstack`,
 * one worker). Locally, run it on its own so no other spec sees the brand:
 *
 *   PORT=7070 E2E_AGAINST_BACKEND=1 npx playwright test --project=fullstack brand-leak
 *
 * Every leak of a run is collected and reported together (route, where, match,
 * snippet) rather than failing on the first.
 */

// ── The test brand ───────────────────────────────────────────────────

const BRAND = {
  productName: "Zebra Labs",
  shortName: "Zebra",
  tagline: "Stripes for every team",
  colors: { primary: "#0f766e", primaryForeground: "#ffffff" },
  // Off, so the About dialog's "Powered by EDDI" must NOT appear anywhere.
  poweredBy: false,
};

const FIXTURES = new URL("./fixtures/", import.meta.url);
const LOGO = readFileSync(new URL("zebra-logo.svg", FIXTURES));
const ICON = readFileSync(new URL("zebra-icon.svg", FIXTURES));

/**
 * What counts as the EDDI brand. Case-sensitive on purpose: `eddi.*` property
 * names, `eddi://` URIs and `eddi_cluster_*` metric names are technical
 * identifiers (docs/white-labeling.md → Not branded) and are lower-case.
 * `\bEDDI\b` does not match inside `EDDI_FOO` (the underscore is a word
 * character), so environment-variable names do not count either.
 */
const LEAK = /\bEDDI\b|[Ll]abs\.ai|LABS\.AI/g;

/**
 * The allow-list. Each entry is an EDDI mention that is correct under a brand
 * and says why. Keep it short: an entry here is a place a customer sees EDDI.
 */
interface Allowance {
  /** Routes it applies to. */
  route: RegExp;
  /** Where on the page (the `where` of a leak), when it is narrower than the page. */
  where?: RegExp;
  /** Must match the snippet around the hit. */
  context: RegExp;
  why: string;
}

const ALLOWED: readonly Allowance[] = [
  {
    route: /^\/manage\/coordinator$/,
    context: /EDDI Cluster/,
    why:
      "The Grafana dashboard is shipped under the title \"EDDI Cluster\" " +
      "(docs/white-labeling.md → The Manager: technical names stay); the text " +
      "names the dashboard an operator has to find in Grafana.",
  },
  {
    route: /^\/manage\/branding$/,
    where: /^<input placeholder>$/,
    context: /^EDDI$/,
    why:
      "The Branding page's empty Product name field shows the value it falls back " +
      "to (docs/white-labeling.md → The Branding page). The test brand sets a name, " +
      "but leaves no seed, so that fallback is EDDI. Admin-only, and the point of " +
      "the hint.",
  },
];

/** Allow-listed hits of this run, printed so a stale entry is noticed. */
const allowedHits: string[] = [];

// ── The routes ───────────────────────────────────────────────────────

/**
 * Every static route of the Manager, read from its route table rather than
 * listed by hand, so a page added to `app.tsx` is covered without touching
 * this file. Routes with a parameter are covered below only where the setup
 * creates a fixture for them.
 */
function managerRoutes(): { manage: string[]; workforce: string[] } {
  const app = readFileSync(new URL("../../src/app.tsx", import.meta.url), "utf8");
  const literal = (s: string) => [...s.matchAll(/\bpath="([^"]+)"/g)].map((m) => m[1]!);
  const isStatic = (p: string) => !p.includes(":") && !p.includes("*");

  // The /workforce block's children are relative paths.
  const start = app.indexOf('<Route path="/workforce"');
  const end = app.indexOf("</Route>", start);
  expect(start, "app.tsx no longer has a <Route path=\"/workforce\"> block").toBeGreaterThan(0);
  const workforceBlock = app.slice(start, end);
  const workforce = [
    "/workforce",
    ...literal(workforceBlock)
      .filter((p) => !p.startsWith("/") && isStatic(p))
      .map((p) => `/workforce/${p}`),
  ];

  const manage = literal(app.slice(0, start) + app.slice(end))
    .filter((p) => p.startsWith("/manage") && isStatic(p));

  return { manage, workforce };
}

/** Every resource type's list page, read from RESOURCE_TYPES. */
function resourceListRoutes(): string[] {
  const src = readFileSync(new URL("../../src/lib/api/resources.ts", import.meta.url), "utf8");
  return [...src.matchAll(/^\s*slug: "([a-z]+)",/gm)].map((m) => `/manage/resources/${m[1]}`);
}

type Shell = "landing" | "manage" | "studio" | "workforce" | "chat";

interface RouteUnderTest {
  path: string;
  shell: Shell;
}

// ── Leak collection ──────────────────────────────────────────────────

interface Leak {
  route: string;
  where: string;
  match: string;
  snippet: string;
}

function snippetAround(text: string, index: number, length: number): string {
  const from = Math.max(0, index - 50);
  const to = Math.min(text.length, index + length + 50);
  return (from > 0 ? "…" : "") + text.slice(from, to).replace(/\s+/g, " ").trim() + (to < text.length ? "…" : "");
}

function findLeaks(route: string, where: string, text: string | null | undefined): Leak[] {
  if (!text) return [];
  const found: Leak[] = [];
  for (const m of text.matchAll(LEAK)) {
    const snippet = snippetAround(text, m.index, m[0].length);
    const allowed = ALLOWED.find(
      (a) => a.route.test(route) && (a.where?.test(where) ?? true) && a.context.test(snippet),
    );
    if (allowed) allowedHits.push(`${route} [${where}] ${snippet}`);
    else found.push({ route, where, match: m[0], snippet });
  }
  return found;
}

function formatLeaks(leaks: Leak[]): string {
  if (leaks.length === 0) return "no leaks";
  const byRoute = new Map<string, Leak[]>();
  for (const l of leaks) byRoute.set(l.route, [...(byRoute.get(l.route) ?? []), l]);
  const lines = [`${leaks.length} brand leak(s) on ${byRoute.size} route(s):`];
  for (const [route, list] of byRoute) {
    lines.push(`  ${route}`);
    for (const l of list) lines.push(`    [${l.where}] "${l.match}" in: ${l.snippet}`);
  }
  return lines.join("\n");
}

// ── Page helpers ─────────────────────────────────────────────────────

/** Every onboarding and tour flag, so no overlay hides the page under test. */
const TOUR_CHAPTERS = [
  "dashboard", "agents", "workflows", "chat", "resources", "conversations", "groups",
  "logs", "secrets", "audit", "schedules", "quotas", "coordinator", "orphans",
];

async function seedOnboardingFlags(page: Page) {
  await page.addInitScript((chapters: string[]) => {
    try {
      localStorage.setItem("eddi-onboarding-welcomed", "true");
      localStorage.setItem("eddi-tour-offers-dismissed", "true");
      for (const c of chapters) localStorage.setItem(`eddi-tour-${c}`, "done");
    } catch {
      /* storage blocked: the overlay's own text is then scanned too */
    }
  }, TOUR_CHAPTERS);
}

const READY: Record<Shell, string> = {
  landing: "h1",
  manage: '[data-testid="app-layout"]',
  studio: '[data-testid="agent-studio"]',
  workforce: '[data-testid="workforce-layout"]',
  chat: ".chat-root",
};

/** Navigate and wait for the page the way the other full-stack specs do. */
async function open(page: Page, route: RouteUnderTest) {
  await page.goto(route.path);
  await page.waitForSelector(READY[route.shell], { timeout: 30_000 });
  if (route.shell !== "chat") {
    const onMocks = await page.evaluate(
      () => (window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__ === true,
    );
    expect(onMocks, `${route.path} ran on MSW mocks, not EDDI — this test proves nothing then`).toBe(false);
    await expect(page.getByTestId("page-loader")).toHaveCount(0, { timeout: 15_000 });
    // Data that never arrives is another spec's failure; scan what is there.
    await expect(page.locator(STILL_LOADING)).toHaveCount(0, { timeout: 15_000 }).catch(() => undefined);
  }
  // Let late text (toasts, lazy panels, the document title) settle.
  await page.waitForTimeout(500);
}

interface PageSnapshot {
  url: string;
  title: string;
  text: string;
  attributes: { attr: string; value: string; tag: string }[];
  headMeta: { key: string; value: string }[];
  images: string[];
  icons: string[];
  primary: string;
  chatAccent: string;
}

async function snapshot(page: Page): Promise<PageSnapshot> {
  return page.evaluate(() => {
    const ATTRS = ["alt", "aria-label", "title", "placeholder", "aria-description", "aria-roledescription"];
    const attributes: { attr: string; value: string; tag: string }[] = [];
    for (const el of Array.from(document.querySelectorAll("*"))) {
      for (const attr of ATTRS) {
        const value = el.getAttribute(attr);
        if (value) attributes.push({ attr, value, tag: el.tagName.toLowerCase() });
      }
    }
    const headMeta = Array.from(document.head.querySelectorAll("meta[content]")).map((m) => ({
      key: m.getAttribute("name") ?? m.getAttribute("property") ?? m.getAttribute("http-equiv") ?? "meta",
      value: m.getAttribute("content") ?? "",
    }));
    const root = getComputedStyle(document.documentElement);
    return {
      url: location.pathname,
      title: document.title,
      text: document.body.innerText,
      attributes,
      headMeta,
      images: Array.from(document.images).map((i) => i.currentSrc || i.src),
      icons: Array.from(document.querySelectorAll('link[rel~="icon"], link[rel="apple-touch-icon"]')).map(
        (l) => (l as HTMLLinkElement).href,
      ),
      primary: root.getPropertyValue("--color-primary").trim().toLowerCase(),
      chatAccent: root.getPropertyValue("--chat-accent").trim().toLowerCase(),
    };
  });
}

/** Meta tags in the server's raw HTML: <title>, description, og:*, twitter:* and the rest. */
async function rawHeadLeaks(request: APIRequestContext, route: string): Promise<Leak[]> {
  const res = await request.get(`${API_BASE}${route}`);
  expect(res.ok(), `GET ${route} answered ${res.status()}`).toBe(true);
  const html = await res.text();
  const head = html.slice(0, Math.max(html.indexOf("</head>"), 0)) || html;
  const leaks: Leak[] = [];
  const title = /<title>([^<]*)<\/title>/i.exec(head)?.[1];
  leaks.push(...findLeaks(route, "raw HTML <title>", title));
  for (const m of head.matchAll(/<meta\b[^>]*>/gi)) {
    const tag = m[0];
    const content = /\bcontent="([^"]*)"/i.exec(tag)?.[1];
    const key = /\b(?:name|property)="([^"]*)"/i.exec(tag)?.[1] ?? "meta";
    leaks.push(...findLeaks(route, `raw HTML meta ${key}`, content));
  }
  return leaks;
}

function isSameOrigin(src: string) {
  try {
    return new URL(src).origin === new URL(API_BASE).origin;
  } catch {
    return false;
  }
}

// ── The test ─────────────────────────────────────────────────────────

async function resetBrand(request: APIRequestContext) {
  const res = await request.delete(`${API_BASE}/administration/branding`);
  expect([200, 204], `DELETE /administration/branding answered ${res.status()}`).toContain(res.status());
}

test.describe("Brand leak — Full Stack", () => {
  test.describe.configure({ mode: "serial", timeout: 900_000 });

  let agent: Awaited<ReturnType<typeof createAndDeployAgent>>;

  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
    agent = await createAndDeployAgent(request);

    await resetBrand(request);
    const current = await request.get(`${API_BASE}/administration/branding`);
    expect(current.ok()).toBe(true);
    const revision = (await current.json()).document.revision as number;

    const put = await request.put(`${API_BASE}/administration/branding`, {
      data: { revision, ...BRAND },
    });
    expect(put.ok(), `PUT /administration/branding: ${put.status()} ${await put.text()}`).toBe(true);

    for (const [slot, body] of [["logo", LOGO], ["icon", ICON]] as const) {
      const up = await request.put(`${API_BASE}/administration/branding/assets/${slot}`, {
        data: body,
        headers: { "Content-Type": "image/svg+xml" },
      });
      expect(up.ok(), `upload ${slot}: ${up.status()} ${await up.text()}`).toBe(true);
    }

    const effective = (await (await request.get(`${API_BASE}/administration/branding`)).json()).effective;
    expect(effective.productName).toBe(BRAND.productName);
    expect(effective.branded).toBe(true);
  });

  test.afterAll(async ({ request }) => {
    // Always restore the default brand, even after a failure, so no later spec
    // runs under "Zebra Labs".
    await resetBrand(request).catch(() => undefined);
    if (agent) {
      await request
        .post(`${API_BASE}/administration/production/undeploy/${agent.agentId}?version=${agent.agentVersion}`)
        .catch(() => undefined);
      await cleanupResource(request, "agentstore/agents", agent.agentId, agent.agentVersion);
      await cleanupResource(request, "workflowstore/workflows", agent.workflowId, agent.workflowVersion);
    }
  });

  test("no page shows the EDDI brand under a custom brand", async ({ page, request }) => {
    const { manage, workforce } = managerRoutes();
    // A broken parse must not pass by walking nothing.
    expect(manage.length, "parsed suspiciously few /manage routes from app.tsx").toBeGreaterThan(30);
    expect(workforce.length).toBeGreaterThanOrEqual(4);

    const routes: RouteUnderTest[] = [
      { path: "/", shell: "landing" },
      { path: "/welcome", shell: "landing" },
      ...manage.map((path) => ({ path, shell: "manage" as const })),
      ...resourceListRoutes().map((path) => ({ path, shell: "manage" as const })),
      // Parameterised routes, with the fixture the setup created.
      { path: `/manage/agentview/${agent.agentId}`, shell: "manage" },
      { path: `/manage/workflowview/${agent.workflowId}`, shell: "manage" },
      { path: `/manage/studio/${agent.agentId}`, shell: "studio" },
      ...workforce.map((path) => ({ path, shell: "workforce" as const })),
      { path: `/chat/production/${agent.agentId}`, shell: "chat" },
      // EDDI's own showcase script: under a brand it must show "not available".
      { path: "/chat/demo/showcase", shell: "chat" },
    ];
    console.log(`brand-leak: walking ${routes.length} routes`);

    await seedOnboardingFlags(page);
    const leaks: Leak[] = [];
    const problems: string[] = [];

    for (const route of routes) {
      leaks.push(...(await rawHeadLeaks(request, route.path)));
      await open(page, route);
      const s = await snapshot(page);
      const at = route.path === s.url ? route.path : `${route.path} → ${s.url}`;

      leaks.push(...findLeaks(at, "document.title", s.title));
      leaks.push(...findLeaks(at, "visible text", s.text));
      for (const a of s.attributes) leaks.push(...findLeaks(at, `<${a.tag} ${a.attr}>`, a.value));
      for (const m of s.headMeta) leaks.push(...findLeaks(at, `live meta ${m.key}`, m.value));

      // The brand must actually be in effect, or "no EDDI" proves nothing.
      if (!s.title.includes(BRAND.productName)) problems.push(`${at}: document.title "${s.title}" does not name the brand`);
      if (s.text.trim().length < 5) problems.push(`${at}: the page rendered no text`);
      const colour = route.shell === "chat" ? s.chatAccent : s.primary;
      if (colour !== BRAND.colors.primary) {
        problems.push(`${at}: ${route.shell === "chat" ? "--chat-accent" : "--color-primary"} is "${colour}", not ${BRAND.colors.primary}`);
      }
      for (const src of [...s.images, ...s.icons]) {
        if (isSameOrigin(src) && !new URL(src).pathname.startsWith("/branding/")) {
          problems.push(`${at}: image ${new URL(src).pathname} is not served from /branding/`);
        }
      }
    }

    // The whole report first, so one run names every leak.
    const report = [formatLeaks(leaks), ...problems.map((p) => `  ${p}`)].join("\n");
    console.log(report);
    console.log([`allow-listed (${allowedHits.length}):`, ...allowedHits].join("\n  "));
    expect(leaks, report).toEqual([]);
    expect(problems, report).toEqual([]);

    // Spot checks that the brand reached the places it should.
    await open(page, { path: "/welcome", shell: "landing" });
    await expect(page.locator("h1")).toHaveText(`Welcome to ${BRAND.productName}`);
    await open(page, { path: "/manage", shell: "manage" });
    expect(
      await page.locator('img[src*="/branding/logo"]').count(),
      "the Manager sidebar does not show the uploaded /branding/logo",
    ).toBeGreaterThan(0);
    await open(page, { path: "/chat/demo/showcase", shell: "chat" });
    await expect(page.getByTestId("demo-unavailable")).toBeVisible();
  });

  test("the API surfaces carry the brand", async ({ request }) => {
    const openapi = await request.get(`${API_BASE}/openapi?format=json`);
    expect(openapi.ok()).toBe(true);
    const info = (await openapi.json()).info as Record<string, unknown>;
    expect(info.title).toBe(`${BRAND.productName} API`);
    // The contact block names LABS.AI; under a brand it is dropped. info.description
    // and the Apache 2.0 licence stay by design (docs/white-labeling.md → API surfaces).
    expect(JSON.stringify(info.contact ?? null)).not.toMatch(/labs\.ai/i);

    // MCP initialize: serverInfo is the product name.
    const mcp = await request.post(`${API_BASE}/mcp`, {
      headers: { Accept: "application/json, text/event-stream", "Content-Type": "application/json" },
      data: {
        jsonrpc: "2.0",
        id: 1,
        method: "initialize",
        params: { protocolVersion: "2025-06-18", capabilities: {}, clientInfo: { name: "brand-leak-e2e", version: "1" } },
      },
    });
    expect(mcp.ok(), `POST /mcp initialize answered ${mcp.status()}`).toBe(true);
    const body = await mcp.text();
    // Streamable HTTP may answer as JSON or as one SSE `data:` event.
    const json = JSON.parse(body.trim().startsWith("{") ? body : /^data:\s*(.*)$/m.exec(body)?.[1] ?? "{}");
    expect(json.result?.serverInfo?.name, body).toBe(BRAND.productName);

    // Swagger UI is branded in a packaged build, which is what this tier runs.
    const swagger = await request.get(`${API_BASE}/q/swagger-ui/`);
    expect(swagger.ok()).toBe(true);
    const swaggerHtml = await swagger.text();
    expect(swaggerHtml).toContain(BRAND.productName);
    expect(findLeaks("/q/swagger-ui/", "raw HTML", swaggerHtml)).toEqual([]);
  });

  test("resetting the brand brings EDDI back", async ({ page, request }) => {
    await resetBrand(request);
    await seedOnboardingFlags(page);

    await open(page, { path: "/welcome", shell: "landing" });
    await expect(page.locator("h1")).toHaveText("Welcome to EDDI");
    expect(await page.title()).toMatch(/\bEDDI\b/);

    for (const route of [
      { path: "/manage", shell: "manage" },
      { path: "/workforce", shell: "workforce" },
      { path: "/chat/demo/showcase", shell: "chat" },
    ] as const) {
      await open(page, route);
      const s = await snapshot(page);
      expect(s.title, `${route.path} title after reset`).toMatch(/\bEDDI\b/);
      expect(s.title).not.toContain(BRAND.productName);
      expect(route.shell === "chat" ? s.chatAccent : s.primary, `${route.path} colour after reset`).not.toBe(
        BRAND.colors.primary,
      );
    }
    await expect(page.getByTestId("demo-unavailable")).toHaveCount(0);

    const openapi = await request.get(`${API_BASE}/openapi?format=json`);
    expect((await openapi.json()).info.title).toMatch(/\bEDDI\b/);
  });
});
