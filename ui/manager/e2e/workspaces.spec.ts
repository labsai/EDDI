import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";
import type { Page } from "@playwright/test";

/**
 * Workspaces, against the real router, the real query client and a real
 * browser.
 *
 * ## Why these exist rather than more unit tests
 *
 * Every defect this feature has actually shipped was a wiring defect that a
 * mocked module graph cannot see: a `?space=` sent to an endpoint that ignored
 * it, a Share entry offered on a deployment with the feature switched off, an
 * invalidation key that prefix-matched nothing. All three look correct in
 * isolation and are only wrong once the pieces are connected — which is this
 * tier's whole job.
 *
 * ## How the backend state is chosen
 *
 * Not with `page.route`. MSW answers from a service worker here, so a mocked
 * request never reaches the browser's network stack and the route handler never
 * fires — `workforce.spec.ts` documents that trap and why its negative
 * assertions were all passing for the wrong reason. Instead the `/workspaces`
 * handler reads a seed from `localStorage`, planted by `addInitScript` before
 * the app boots. The seam lives entirely in the mock layer; the application has
 * no idea it exists.
 *
 * The default is `enabled: false`, matching the backend's own default, so the
 * first block below asserts the state most deployments are actually in.
 */

const ALICE = "alice@example.com";
const PERSONAL = { id: `user:${ALICE}`, kind: "personal", label: ALICE };
const TEAM = { id: "team:engineering", kind: "team", label: "engineering" };

/** The fixtures' own workspace assignments — see `AGENTS_MOCK` in handlers.ts. */
const IN_PERSONAL_SPACE = ["Support Agent", "FAQ Agent"];
const IN_TEAM_SPACE = ["Appointment Scheduler", "Invoice Analyst"];

async function seedWorkspaces(
  page: Page,
  info: {
    enabled: boolean;
    principal?: string | null;
    spaces?: { id: string; kind: string; label: string }[];
    seesEverything?: boolean;
  },
) {
  await page.addInitScript(
    ([key, value]) => localStorage.setItem(key as string, value as string),
    [
      "eddi-e2e-workspaces",
      JSON.stringify({
        enabled: info.enabled,
        principal: info.principal ?? ALICE,
        defaultSpace: PERSONAL.id,
        spaces: info.spaces ?? [PERSONAL, TEAM],
        seesEverything: info.seesEverything ?? false,
      }),
    ],
  );
}

/*
 * The chosen space is remembered in localStorage, and deliberately NOT cleared
 * here. Playwright gives each test a fresh context seeded from
 * `e2e/storage-state.json`, which does not carry the key — so tests start clean
 * on their own. An earlier version cleared it with `addInitScript`, which runs
 * on every navigation *including* `page.reload()`, and so wiped the very
 * preference the reload test exists to check.
 */

async function openAgentMenu(page: Page, agentId: string) {
  await page.getByTestId(`agent-menu-${agentId}`).click();
}

test.describe("workspaces switched off (the default deployment)", () => {
  test.beforeEach(async ({ page }) => {
    await page.goto("/manage/agents");
    await waitForApp(page);
  });

  test("offers no switcher and no Share action", async ({ page }) => {
    // The whole degradation guarantee in one test. Ownership IS recorded while
    // enforcement is off — the fixtures carry ownerId and spaceId — so a UI
    // reading the descriptors alone would draw all of this. It must not.
    await expect(page.getByTestId("space-switcher")).toHaveCount(0);

    await openAgentMenu(page, "agent1");
    await expect(page.getByRole("menuitem", { name: /share/i })).toHaveCount(0);
    // The menu did open, so the absence above is absence and not a missed click.
    await expect(page.getByRole("menuitem", { name: /export/i })).toBeVisible();
  });

  test("shows no ownership badge, including on a resource owned by someone else", async ({
    page,
  }) => {
    // agent4 is bob's and published. With nothing enforced there is no
    // ownership story to tell, so labelling it would name a distinction the
    // deployment does not have.
    await expect(page.getByTestId("ownership-badge-published")).toHaveCount(0);
    await expect(page.getByTestId("ownership-badge-shared")).toHaveCount(0);
  });

  test("still lists every agent", async ({ page }) => {
    for (const name of [...IN_PERSONAL_SPACE, ...IN_TEAM_SPACE]) {
      await expect(page.getByText(name, { exact: true })).toBeVisible();
    }
  });
});

test.describe("workspaces enforced", () => {
  test.beforeEach(async ({ page }) => {
    await seedWorkspaces(page, { enabled: true });
    await page.goto("/manage/agents");
    await waitForApp(page);
  });

  test("the switcher narrows the listing to one space, and back", async ({ page }) => {
    // The assertion that matters is the one that failed to exist: the switcher
    // shipped sending `?space=` to an endpoint that ignored it, so it changed
    // the URL and nothing else. Both directions are checked — a filter that
    // empties the list is not the same as one that selects.
    await expect(page.getByText("Support Agent", { exact: true })).toBeVisible();
    await expect(page.getByText("Invoice Analyst", { exact: true })).toBeVisible();

    await page.getByTestId("space-switcher").click();
    await page.getByTestId(`space-option-${TEAM.id}`).click();

    // Positive first, for the same reason as the reload test below: an absence
    // asserted against an unrendered list is not an absence.
    for (const name of IN_TEAM_SPACE) {
      await expect(page.getByText(name, { exact: true })).toBeVisible();
    }
    for (const name of IN_PERSONAL_SPACE) {
      await expect(page.getByText(name, { exact: true })).toHaveCount(0);
    }

    await page.getByTestId("space-switcher").click();
    await page.getByTestId("space-option-all").click();

    for (const name of IN_PERSONAL_SPACE) {
      await expect(page.getByText(name, { exact: true })).toBeVisible();
    }
  });

  test("remembers the chosen space across a reload", async ({ page }) => {
    await page.getByTestId("space-switcher").click();
    await page.getByTestId(`space-option-${TEAM.id}`).click();
    await expect(page.getByText("Support Agent", { exact: true })).toHaveCount(0);

    await page.reload();
    await waitForApp(page);

    // Positive assertion FIRST. `toHaveCount(0)` is satisfied by a page that has
    // not rendered its rows yet, so leading with it would pass against the
    // loading skeleton and a flash a moment later would go unseen. Waiting for
    // a team agent to be on screen means the list has actually rendered before
    // the absence is claimed.
    await expect(page.getByText("Invoice Analyst", { exact: true })).toBeVisible();
    await expect(page.getByText("Support Agent", { exact: true })).toHaveCount(0);
  });

  test("keeps the switcher with the listing it actually filters", async ({ page }) => {
    // It used to sit in the global top bar, which reads as app-wide — but only
    // the agent listing honours `?space=`, so on any other page the bar
    // announced a filter that was not being applied. The top bar is also
    // `hidden md:flex`, so a space chosen on a desktop narrowed a phone's list
    // with no control anywhere to see or clear it.
    const switcher = page.getByTestId("space-switcher");
    await expect(switcher).toBeVisible();

    const inAgentsPage = page.locator('[data-tour="agents-search"]').getByTestId("space-switcher");
    await expect(inAgentsPage).toHaveCount(1);

    await page.goto("/manage/resources");
    await waitForApp(page);
    await expect(page.getByTestId("space-switcher")).toHaveCount(0);
  });

  test("offers a way out of a workspace with nothing in it", async ({ page }) => {
    // "No agents yet" under an active filter is simply false — the agents exist
    // somewhere else — and the Create button that came with it compounded the
    // lie, because a new agent lands in the default space and would not show up
    // in the filter either.
    await seedWorkspaces(page, {
      enabled: true,
      spaces: [PERSONAL, TEAM, { id: "team:finance", kind: "team", label: "finance" }],
    });
    await page.goto("/manage/agents");
    await waitForApp(page);

    await page.getByTestId("space-switcher").click();
    await page.getByTestId("space-option-team:finance").click();

    await expect(page.getByText(/no agents in this workspace/i)).toBeVisible();
    await page.getByRole("button", { name: /show all workspaces/i }).click();

    await expect(page.getByText("Support Agent", { exact: true })).toBeVisible();
  });

  test("the list view carries the badge and the share action too", async ({ page }) => {
    // A user whose stored view mode is "list" could otherwise neither see who
    // owns a resource nor reach sharing at all.
    await page.getByTestId("view-toggle-list").click();
    await expect(page.getByTestId("agent-list")).toBeVisible();

    await expect(page.getByTestId("agent-row-share-agent1")).toBeVisible();
    await expect(page.getByTestId("ownership-badge-published")).toHaveCount(1);

    await page.getByTestId("agent-row-share-agent1").click();
    await expect(page.getByTestId("share-dialog")).toBeVisible();
  });

  test("offers only what each row actually permits", async ({ page }) => {
    // The gap this closes: before the backend reported a level, every row got
    // every action and the user learned the truth from a 403. agent3 is shared
    // for chatting only; agent4 is somebody else's, published, so readable but
    // not deletable; agent1 is the caller's own.
    await page.getByTestId("agent-menu-agent3").click();
    await expect(page.getByText(/shared with you for chatting only/i)).toBeVisible();
    await expect(page.getByRole("menuitem", { name: /share/i })).toHaveCount(0);
    await expect(page.getByRole("menuitem", { name: /delete/i })).toHaveCount(0);
    await expect(page.getByRole("menuitem", { name: /export/i })).toHaveCount(0);
    await page.keyboard.press("Escape");

    // VIEW: read and export, but neither delete nor re-share.
    await page.getByTestId("agent-menu-agent4").click();
    await expect(page.getByRole("menuitem", { name: /export/i })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: /delete/i })).toHaveCount(0);
    await expect(page.getByRole("menuitem", { name: /share/i })).toHaveCount(0);
    await page.keyboard.press("Escape");

    // OWN: everything. Asserting this is what stops the test passing because
    // the menu is broken rather than because the gating works.
    await page.getByTestId("agent-menu-agent1").click();
    await expect(page.getByRole("menuitem", { name: /export/i })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: /delete/i })).toBeVisible();
    await expect(page.getByRole("menuitem", { name: /share/i })).toBeVisible();
  });

  test("gates the controls that sit outside the menu too", async ({ page }) => {
    // Deploying is EDIT and the config view is VIEW, and both live outside the
    // ⋯ menu — which is exactly how they were missed the first time. A USE
    // holder offered "Undeploy from production" gets a 403, which is the same
    // broken-looking outcome the row gating exists to remove.
    await expect(page.getByTestId("agent-deploy-toggle-agent1")).toBeVisible();
    await expect(page.getByTestId("agent-deploy-toggle-agent3")).toHaveCount(0);

    // And the name stops being a link into a page that would refuse every panel,
    // while the agent itself stays listed — they may still converse with it.
    await expect(page.getByTestId("agent-open-agent1")).toBeVisible();
    await expect(page.getByTestId("agent-open-agent3")).toHaveCount(0);
    await expect(page.getByTestId("agent-card-agent3")).toBeVisible();
  });

  test("the list view gates its row actions the same way", async ({ page }) => {
    await page.getByTestId("view-toggle-list").click();
    await expect(page.getByTestId("agent-list")).toBeVisible();

    // Positive first: the row rendered at all, so the absences below are
    // absences rather than an unrendered table.
    await expect(page.getByTestId("agent-row-share-agent1")).toBeVisible();
    await expect(page.getByTestId("agent-row-share-agent3")).toHaveCount(0);
    await expect(page.getByTestId("agent-row-share-agent4")).toHaveCount(0);

    // The other three row actions carry the same gates and had no assertion of
    // their own — deleting any one of them left this test green.
    const row3 = page.getByTestId("agent-row-agent3");
    await expect(row3).toBeVisible();
    await expect(row3.getByRole("button", { name: /duplicate/i })).toHaveCount(0);
    await expect(row3.getByRole("button", { name: /export/i })).toHaveCount(0);
    await expect(row3.getByRole("button", { name: /delete/i })).toHaveCount(0);

    const row4 = page.getByTestId("agent-row-agent4");
    await expect(row4.getByRole("button", { name: /export/i })).toBeVisible();
    await expect(row4.getByRole("button", { name: /delete/i })).toHaveCount(0);
  });

  test("hides the switcher for a user with only their own space", async ({ page }) => {
    await seedWorkspaces(page, { enabled: true, spaces: [PERSONAL] });
    await page.goto("/manage/agents");
    await waitForApp(page);

    await expect(page.getByTestId("space-switcher")).toHaveCount(0);
    // The feature is on, so the Share action is still offered — the switcher is
    // hidden because it has nothing to offer, not because sharing is off.
    await openAgentMenu(page, "agent1");
    await expect(page.getByRole("menuitem", { name: /share/i })).toBeVisible();
  });

  test("badges someone else's published agent and leaves your own alone", async ({
    page,
  }) => {
    // agent4 is bob@example.com's, published. agent1 is alice's own.
    await expect(page.getByTestId("ownership-badge-published")).toHaveCount(1);

    const own = page.getByTestId("agent-card-agent1");
    await expect(own.getByTestId("ownership-badge-published")).toHaveCount(0);
    await expect(own.getByTestId("ownership-badge-shared")).toHaveCount(0);
  });
});

test.describe("sharing", () => {
  test.beforeEach(async ({ page }) => {
    await seedWorkspaces(page, { enabled: true });
    await page.goto("/manage/agents");
    await waitForApp(page);
    await openAgentMenu(page, "agent1");
    await page.getByRole("menuitem", { name: /share/i }).click();
    await expect(page.getByTestId("share-dialog")).toBeVisible();
  });

  test("names what the cascade touched", async ({ page }) => {
    // Sharing an agent reaches the workflows, rule sets and output sets beneath
    // it. That is invisible in the request and surprising in the result, so the
    // dialog has to say it happened.
    await page.getByTestId("share-subject-input").fill("bob@example.com");
    await page.getByTestId("share-submit").click();

    await expect(page.getByTestId("share-cascade-summary")).toBeVisible();
  });

  test("asks a second time before handing over ownership", async ({ page }) => {
    await page.getByTestId("share-subject-input").fill("bob@example.com");
    await page.getByTestId("share-level-select").selectOption("OWN");
    await page.getByTestId("share-submit").click();

    // First click warns instead of acting. The cascade summary only renders
    // after a completed change, so its absence is what "nothing happened" looks
    // like here — and unlike a toast it does not expire on a timer.
    await expect(page.getByTestId("share-owner-warning")).toBeVisible();
    await expect(page.getByTestId("share-cascade-summary")).toHaveCount(0);

    await page.getByTestId("share-submit").click();
    await expect(page.getByTestId("share-cascade-summary")).toBeVisible();
  });

  test("changing the level withdraws a pending transfer", async ({ page }) => {
    await page.getByTestId("share-subject-input").fill("bob@example.com");
    await page.getByTestId("share-level-select").selectOption("OWN");
    await page.getByTestId("share-submit").click();
    await expect(page.getByTestId("share-owner-warning")).toBeVisible();

    await page.getByTestId("share-level-select").selectOption("VIEW");
    await expect(page.getByTestId("share-owner-warning")).toHaveCount(0);
  });

  test("refuses a subject with an unrecognised prefix, visibly", async ({ page }) => {
    // "group:" is a plausible typo for "team:", and a share with a subject
    // nobody holds looks exactly like a successful one.
    await page.getByTestId("share-subject-input").fill("group:engineering");
    await page.getByTestId("share-submit").click();

    // The refusal has to be VISIBLE, or this test passes just as happily
    // against a button that does nothing whatsoever. The toast is the user's
    // only signal here, so it is the assertion.
    await expect(page.getByText(/use 'user:' or 'team:'/i)).toBeVisible();
    await expect(page.getByTestId("share-cascade-summary")).toHaveCount(0);
    await expect(page.getByTestId("share-subject-input")).toHaveValue("group:engineering");
  });

  test("withdraws a confirmed transfer when the subject is retyped", async ({ page }) => {
    // The confirmation must be bound to the subject it warned about — otherwise
    // arming it for one person and retyping another hands ownership to someone
    // the user was never warned about.
    const input = page.getByTestId("share-subject-input");
    await input.fill("bob@example.com");
    await page.getByTestId("share-level-select").selectOption("OWN");
    await page.getByTestId("share-submit").click();
    await expect(page.getByTestId("share-owner-warning")).toBeVisible();

    await input.fill("carol@example.com");

    await expect(page.getByTestId("share-owner-warning")).toHaveCount(0);
    await page.getByTestId("share-submit").click();
    // Re-armed for carol rather than transferred to her.
    await expect(page.getByTestId("share-owner-warning")).toBeVisible();
    await expect(page.getByTestId("share-cascade-summary")).toHaveCount(0);
  });

  test("closes on Escape", async ({ page }) => {
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("share-dialog")).toHaveCount(0);
  });
});
