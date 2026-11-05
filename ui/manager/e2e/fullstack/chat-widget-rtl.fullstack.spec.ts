import { test, expect, type Locator } from "@playwright/test";
import { waitForBackend } from "./fullstack-helpers";

/**
 * The Chat UI in a right-to-left language (frontend unification plan, phase 3
 * exit criterion: "RTL e2e on the widget").
 *
 * The widget has no Playwright tier of its own, and the MSW tier serves only
 * the Manager, so this runs against the real server, which serves `/chat`. The
 * demo route needs no agent: `/chat/demo/showcase?lang=ar` opens the scripted
 * conversation in Arabic. (`ChatRoute` turns the demo off under a brand; the
 * brand-leak spec resets the brand when it is done.)
 *
 * It checks the document direction and that the layout follows it: the
 * composer's attach button sits on the right of the message box in Arabic and
 * on its left in English, and an agent message's avatar is on the right.
 */

async function centreX(locator: Locator): Promise<number> {
  const box = await locator.boundingBox();
  if (!box) throw new Error("element has no layout box");
  return box.x + box.width / 2;
}

test.describe("Chat UI — right to left", () => {
  test.beforeAll(async ({ request }) => {
    await waitForBackend(request);
  });

  test("?lang=ar lays the widget out right to left", async ({ page }) => {
    await page.goto("/chat/demo/showcase?lang=ar");

    const html = page.locator("html");
    await expect(html).toHaveAttribute("dir", "rtl");
    await expect(html).toHaveAttribute("lang", "ar");

    const input = page.getByTestId("chat-input");
    await expect(input).toBeVisible({ timeout: 15_000 });
    const attach = page.getByTestId("chat-attach-btn");
    // Mirrored: the attach button leads the message box, on its right.
    expect(await centreX(attach)).toBeGreaterThan(await centreX(input));

    // The scripted greeting is an agent message; its avatar leads it, at the
    // right edge.
    const avatar = page.locator(".message--agent .message__avatar").first();
    await expect(avatar).toBeAttached();
    const viewport = page.viewportSize();
    expect(viewport).not.toBeNull();
    expect(await centreX(avatar)).toBeGreaterThan(viewport!.width / 2);
  });

  test("an explicit ?lang=en stays left to right", async ({ page }) => {
    await page.goto("/chat/demo/showcase?lang=en");

    await expect(page.locator("html")).toHaveAttribute("dir", "ltr");
    const input = page.getByTestId("chat-input");
    await expect(input).toBeVisible({ timeout: 15_000 });
    expect(await centreX(page.getByTestId("chat-attach-btn"))).toBeLessThan(await centreX(input));
  });
});
