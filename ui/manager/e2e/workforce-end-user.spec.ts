import type { Page } from "@playwright/test";
import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";
import { MOCK_CATALOG_AGENTS, MOCK_CATALOG_GROUPS } from "@/test/mocks/catalog-fixtures";

/**
 * Workforce as a plain `eddi-user` (frontend-unification plan, phase 2).
 *
 * The mock backend answers every request regardless of role, so "no page
 * returns 403" cannot be asserted from status codes here. It is asserted from
 * what the backend would refuse instead: every authoring store an `eddi-user`
 * is denied (`/agentstore`, `/groupstore`, `/deploymentstore`, and the
 * `/administration` resources) must not be requested at all while they move
 * through Workforce. Requests are collected with `page.on("request")`, which —
 * unlike `page.route` — also sees the ones MSW's service worker answers.
 *
 * The role comes from `eddi-mock-roles` (`lib/mock-roles.ts`), a seam that only
 * a dev build running the mock API reads.
 */

const FORBIDDEN_FOR_END_USERS = /\/(agentstore|groupstore|deploymentstore|administration)\//;

const AGENT = MOCK_CATALOG_AGENTS[0];
const GROUP = MOCK_CATALOG_GROUPS[0];

function collectForbiddenRequests(page: Page): string[] {
  const seen: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (FORBIDDEN_FOR_END_USERS.test(url.pathname)) seen.push(`${request.method()} ${url.pathname}`);
  });
  return seen;
}

test.describe("Workforce for an eddi-user", () => {
  let forbidden: string[];

  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem("eddi-mock-roles", "eddi-user");
    });
    forbidden = collectForbiddenRequests(page);
  });

  test.afterEach(() => {
    expect(forbidden, "an eddi-user's Workforce requested a store the backend refuses them (403)").toEqual([]);
  });

  test("the dashboard is the catalog: listed agents and teams, no operator, no authoring", async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);

    await expect(page.getByTestId("workforce-catalog")).toBeVisible();
    for (const agent of MOCK_CATALOG_AGENTS) {
      await expect(page.getByTestId(`catalog-agent-${agent.agentId}`)).toBeVisible();
    }
    for (const group of MOCK_CATALOG_GROUPS) {
      await expect(page.getByTestId(`catalog-group-${group.groupId}`)).toBeVisible();
    }
    // Exactly what the catalog offered — nothing read from anywhere else.
    await expect(page.getByTestId(/^catalog-agent-/)).toHaveCount(MOCK_CATALOG_AGENTS.length);
    await expect(page.getByTestId(/^catalog-group-/)).toHaveCount(MOCK_CATALOG_GROUPS.length);

    // The Platform Operator never appears: no drawer, no link, no catalog card.
    await expect(page.getByTestId("workforce-operator-link")).toHaveCount(0);
    await expect(page.getByText(/Platform Operator/i)).toHaveCount(0);

    // No building: no "assemble" button, no Insights.
    await expect(page.locator('a[href="/workforce/new"]')).toHaveCount(0);
    await expect(page.locator('a[href="/workforce/analytics"]')).toHaveCount(0);
  });

  test("a listed team's board, history and a member thread open without store reads", async ({ page }) => {
    await page.goto(`/workforce/${GROUP.groupId}?version=${GROUP.version}`);
    await waitForApp(page);
    await expect(page.getByTestId("workforce-layout")).toBeVisible();
    await expect(page.getByTestId("board-settings-link")).toHaveCount(0);

    await page.goto(`/workforce/${GROUP.groupId}/history`);
    await waitForApp(page);
    await expect(page.getByTestId("workforce-layout")).toBeVisible();

    await page.goto(`/workforce/${GROUP.groupId}/thread/${AGENT.agentId}`);
    await waitForApp(page);
    await expect(page.getByTestId("workforce-layout")).toBeVisible();
  });

  test("chat with a listed agent opens without store reads", async ({ page }) => {
    await page.goto(`/workforce/chat?agentId=${AGENT.agentId}&agentName=${encodeURIComponent(AGENT.name)}`);
    await waitForApp(page);
    await expect(page.getByTestId("workforce-layout")).toBeVisible();
  });

  for (const [path, expected] of [
    ["/workforce/new", "/workforce"],
    ["/workforce/analytics", "/workforce"],
    [`/workforce/${GROUP.groupId}/settings`, `/workforce/${GROUP.groupId}`],
  ] as const) {
    test(`${path} is an author screen and sends an eddi-user on to ${expected}`, async ({ page }) => {
      await page.goto(path);
      await waitForApp(page);
      await expect(page).toHaveURL((url) => url.pathname === expected);
    });
  }

  test("/welcome skips the chooser and lands in Workforce (D6)", async ({ page }) => {
    await page.goto("/welcome");
    await expect(page).toHaveURL((url) => url.pathname === "/workforce");
    await expect(page.getByTestId("workforce-catalog")).toBeVisible();
  });
});

test.describe("Workforce for an eddi-admin", () => {
  test("links to the Platform Operator instead of mounting its drawer (D5)", async ({ page }) => {
    await page.addInitScript(() => {
      window.localStorage.setItem("eddi-mock-roles", "eddi-admin");
    });
    await page.goto("/workforce");
    await waitForApp(page);
    const link = page.getByTestId("workforce-operator-link");
    await expect(link).toBeVisible();
    await expect(link).toHaveAttribute("href", "/manage/operator");
  });
});
