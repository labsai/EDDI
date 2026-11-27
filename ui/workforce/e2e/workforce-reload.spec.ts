import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";

/**
 * T1.15 (frontend unification plan, phase 4): a reload or a second tab returns
 * to the same Workforce conversation, because its id lives in the URL.
 *
 * The mock's conversation `conv1` belongs to `agent1` (the simple-log handler
 * answers every read as agent1's). The widget's own direct route
 * (`/chat/:environment/:agentId`, risk R10) is a public contract pinned by the
 * Chat UI's `location.test.tsx`; this change does not touch it.
 */

const CHAT = "/workforce/chat?agentId=agent1&conversationId=conv1";

test.describe("Workforce chat survives a reload (T1.15)", () => {
  test("a reload returns to the same conversation", async ({ page }) => {
    await page.goto(CHAT);
    await waitForApp(page);
    await expect(page.getByTestId("chat-input")).toBeEnabled();
    await expect(page).toHaveURL(/conversationId=conv1/);

    await page.reload();
    await waitForApp(page);
    await expect(page).toHaveURL(/agentId=agent1&conversationId=conv1$/);
    await expect(page.getByTestId("chat-input")).toBeEnabled();
  });

  test("a second tab opens the same conversation", async ({ page, context }) => {
    await page.goto(CHAT);
    await waitForApp(page);
    await expect(page.getByTestId("chat-input")).toBeEnabled();

    const second = await context.newPage();
    await second.goto(page.url());
    await waitForApp(second);
    await expect(second).toHaveURL(/agentId=agent1&conversationId=conv1$/);
    await expect(second.getByTestId("chat-input")).toBeEnabled();
    await second.close();
  });
});
