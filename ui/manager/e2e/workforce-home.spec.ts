import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";
import { MOCK_CATALOG_AGENTS, MOCK_CATALOG_GROUPS } from "@/test/mocks/catalog-fixtures";

/**
 * Workforce Home (frontend unification plan, T1.4, phase 4) as a plain
 * `eddi-user`: a composer first, the catalog as cards with each agent's
 * appearance (B4), per-agent starters that start a chat, and the recent
 * conversations.
 */

const ADA = MOCK_CATALOG_AGENTS[0];
const TRAVEL = MOCK_CATALOG_AGENTS[1];

test.describe("Workforce Home for an eddi-user", () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => window.localStorage.setItem("eddi-mock-roles", "eddi-user"));
  });

  test("shows the composer first, then the catalog cards with their appearance", async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);

    const composer = page.getByTestId("home-composer");
    await expect(composer).toBeVisible();
    await expect(page.getByTestId("home-agent-picker")).toHaveValue(ADA.agentId);

    // The appearance's display name and accent colour on the agent's card.
    const card = page.getByTestId(`catalog-agent-${ADA.agentId}`);
    await expect(card).toContainText(ADA.appearance.displayName);
    await expect(card.getByRole("button")).toHaveCSS("border-top-color", "rgb(10, 125, 85)");
    // An agent without appearance: its stored name.
    await expect(page.getByTestId(`catalog-agent-${TRAVEL.agentId}`)).toContainText(TRAVEL.name);
    for (const group of MOCK_CATALOG_GROUPS) {
      await expect(page.getByTestId(`catalog-group-${group.groupId}`)).toBeVisible();
    }

    // The composer sits above the catalog.
    const composerBox = await composer.boundingBox();
    const cardBox = await card.boundingBox();
    expect(composerBox!.y).toBeLessThan(cardBox!.y);
  });

  test("a starter starts a chat with its text", async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);

    const starters = page.getByTestId("home-starters");
    await expect(starters.getByRole("button")).toHaveText([...ADA.appearance.starters]);
    await page.getByTestId("home-starter-0").click();

    // A new conversation, its id in the URL (T1.15), and the starter sent as the
    // first message — the text itself never in the URL.
    await expect(page).toHaveURL(new RegExp(`/workforce/chat\\?agentId=${ADA.agentId}&conversationId=[^&]+$`));
    expect(page.url()).not.toContain("order");
    await expect(page.getByText(ADA.appearance.starters[0]).first()).toBeVisible();
  });

  test("typing in the composer starts a chat with the picked agent", async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);

    await page.getByTestId("home-agent-picker").selectOption(TRAVEL.agentId);
    await page.getByTestId("home-composer-input").fill("A weekend in Lisbon");
    await page.getByTestId("home-composer-send").click();

    await expect(page).toHaveURL(new RegExp(`/workforce/chat\\?agentId=${TRAVEL.agentId}&conversationId=[^&]+$`));
    await expect(page.getByText("A weekend in Lisbon").first()).toBeVisible();
  });

  test("lists the recent conversations", async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);
    const recent = page.getByTestId("home-recent");
    await expect(recent).toBeVisible();
    await expect(recent.getByRole("link")).toHaveCount(5);
  });
});
