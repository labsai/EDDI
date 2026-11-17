import type { Page } from "@playwright/test";
import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";

/**
 * The Workforce sidebar's conversations (frontend unification plan, T1.1 and
 * T1.2, phase 4): the signed-in person's chats and team sessions grouped by
 * date with the pinned ones on top, each shown by its title, renamed inline,
 * pinned and deleted.
 *
 * MSW answers from a service worker, so requests are observed with
 * `page.on("request")`, which sees them; `page.route` would not (see
 * workforce.spec.ts). The mock fixtures: `conv1` titled and pinned, `conv3`
 * titled, the rest untitled (agent name and date).
 */

function collectWrites(page: Page): Array<{ method: string; path: string; body: string | null }> {
  const writes: Array<{ method: string; path: string; body: string | null }> = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (!url.pathname.startsWith("/conversationstore/conversations/")) return;
    if (request.method() === "GET") return;
    writes.push({ method: request.method(), path: url.pathname, body: request.postData() });
  });
  return writes;
}

test.describe("Workforce sidebar — your conversations", () => {
  for (const role of ["eddi-user", "eddi-admin"] as const) {
    test(`groups chats by date with the pinned ones on top (${role})`, async ({ page }) => {
      await page.addInitScript((r) => window.localStorage.setItem("eddi-mock-roles", r), role);
      await page.goto("/workforce");
      await waitForApp(page);

      const sessions = page.getByTestId("workforce-sessions");
      await expect(sessions).toBeVisible();
      const pinned = sessions.getByTestId("session-group-pinned");
      await expect(pinned.getByTestId("session-chat-conv1-label")).toHaveText("Where is order 1042?");
      await expect(sessions.getByTestId("session-group-today").getByTestId("session-chat-conv3-label")).toHaveText(
        "Refund for a damaged parcel",
      );
      await expect(sessions.getByTestId("session-group-previous7")).toBeVisible();
      // The group order: pinned first, then today.
      const keys = await sessions.locator('[data-testid^="session-group-"]').evaluateAll((els) =>
        els.map((el) => el.getAttribute("data-testid")),
      );
      expect(keys.slice(0, 2)).toEqual(["session-group-pinned", "session-group-today"]);
    });
  }

  test("renames a chat inline", async ({ page }) => {
    const writes = collectWrites(page);
    await page.goto("/workforce");
    await waitForApp(page);

    const row = page.getByTestId("session-chat-conv3");
    await row.hover();
    await page.getByTestId("session-chat-conv3-rename").click();
    const input = page.getByTestId("session-chat-conv3-rename-input");
    await input.fill("Parcel refund");
    await input.press("Enter");

    await expect(page.getByTestId("session-chat-conv3-label")).toHaveText("Parcel refund");
    await expect.poll(() => writes.filter((w) => w.method === "PATCH")).toEqual([
      { method: "PATCH", path: "/conversationstore/conversations/conv3", body: JSON.stringify({ title: "Parcel refund" }) },
    ]);
  });

  test("pins and unpins a chat", async ({ page }) => {
    const writes = collectWrites(page);
    await page.goto("/workforce");
    await waitForApp(page);

    await page.getByTestId("session-chat-conv3").hover();
    await page.getByTestId("session-chat-conv3-pin").click();
    await expect(page.getByTestId("session-group-pinned").getByTestId("session-chat-conv3")).toBeVisible();

    await page.getByTestId("session-chat-conv1").hover();
    await page.getByTestId("session-chat-conv1-pin").click();
    await expect(page.getByTestId("session-group-pinned").getByTestId("session-chat-conv1")).toHaveCount(0);

    await expect
      .poll(() => writes.filter((w) => w.method === "PATCH").map((w) => `${w.path} ${w.body}`))
      .toEqual([
        `/conversationstore/conversations/conv3 ${JSON.stringify({ pinned: true })}`,
        `/conversationstore/conversations/conv1 ${JSON.stringify({ pinned: false })}`,
      ]);
  });

  test("deletes a chat only after confirming", async ({ page }) => {
    const writes = collectWrites(page);
    await page.goto("/workforce");
    await waitForApp(page);

    await page.getByTestId("session-chat-conv2").hover();
    await page.getByTestId("session-chat-conv2-delete").click();
    await expect(page.getByTestId("alert-dialog-confirm")).toBeVisible();
    // Cancel deletes nothing.
    await page.getByTestId("alert-dialog-cancel").click();
    await expect(page.getByTestId("alert-dialog-confirm")).toHaveCount(0);
    await expect(page.getByTestId("session-chat-conv2")).toBeVisible();
    expect(writes).toEqual([]);

    await page.getByTestId("session-chat-conv2").hover();
    await page.getByTestId("session-chat-conv2-delete").click();
    await page.getByTestId("alert-dialog-confirm").click();
    await expect(page.getByTestId("session-chat-conv2")).toHaveCount(0);
    await expect.poll(() => writes.map((w) => `${w.method} ${w.path}`)).toEqual([
      "DELETE /conversationstore/conversations/conv2",
    ]);
  });

  test("opens a chat from the sidebar with both ids in the URL", async ({ page }) => {
    await page.goto("/workforce");
    await waitForApp(page);
    await page.getByTestId("session-chat-conv1").click();
    await expect(page).toHaveURL(/\/workforce\/chat\?agentId=agent1&conversationId=conv1$/);
  });
});
