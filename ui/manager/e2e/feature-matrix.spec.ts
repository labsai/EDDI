import type { Page } from "@playwright/test";
import { test, expect } from "./fixtures";
import { waitForApp } from "./e2e-helpers";

/**
 * Conversation feature matrix — what each Manager conversation surface can do
 * TODAY (frontend unification plan, §1.3 and phase 0).
 *
 * One row per capability, one column per surface. Every cell is one test,
 * tagged `@surface:<id>` and `@capability:<id>`, so `--grep @surface:operator`
 * prints one surface's column. A cell is:
 *
 *  - `yes`  — asserted here against the MSW backend;
 *  - `unit` — supported, but the MSW tier has no fixture to reach it (an
 *             activated operator, an agent that asks for a secret); skipped
 *             with the unit test that covers it, so the gap stays visible;
 *  - `no`   — not supported; `test.fixme` with the reason. When a phase brings
 *             the capability to the surface, the cell flips to `yes` and its
 *             assertion lands with it. Phase 3's exit criterion is this table
 *             showing the union of capabilities on every agent surface.
 *
 * The widget's column lives in ui/chat (`src/feature-matrix.test.tsx`), which
 * has no Playwright tier.
 */

type SurfaceId = "chat-panel" | "operator" | "workforce-thread";
type CapabilityId =
  | "katex"
  | "highlight"
  | "output-images"
  | "quick-replies"
  | "input-field"
  | "secret-input"
  | "history"
  | "inline-hitl"
  | "i18n";

type Cell =
  | { status: "yes"; check: (page: Page) => Promise<void> }
  | { status: "unit" | "no"; reason: string };

interface Surface {
  path: string;
  /** Proves the surface itself rendered, before any capability is looked at. */
  ready: (page: Page) => Promise<void>;
}

const SURFACES: Record<SurfaceId, Surface> = {
  "chat-panel": {
    path: "/manage/chat",
    ready: async (page) => {
      await waitForApp(page);
      await expect(page.getByTestId("agent-selector")).toBeVisible();
    },
  },
  operator: {
    path: "/manage/operator",
    ready: (page) => waitForApp(page),
  },
  "workforce-thread": {
    path: "/workforce/board1/thread/agent1",
    ready: async (page) => {
      await waitForApp(page);
      await expect(page.getByTestId("thread-new-conversation")).toBeVisible();
    },
  },
};

/** Start a conversation with the mock "Support Agent" in the chat panel. */
async function startSupportChat(page: Page) {
  await page.getByTestId("agent-selector").click();
  await page.getByText("Support Agent").first().click();
  await expect(page.getByTestId("chat-input")).toBeVisible({ timeout: 15_000 });
}

/** The UI follows the stored language: lang/dir on <html> say which bundle renders. */
const i18nCell: Cell = {
  status: "yes",
  check: async (page) => {
    await expect(page.locator("html")).toHaveAttribute("lang", "de");
    await expect(page.locator("html")).toHaveAttribute("dir", "ltr");
  },
};

const NO_RICH = "renders markdown without the shared renderer's KaTeX/highlight.js (phase 6 moves the operator; the member thread is still its own)";

/**
 * Mirrors RICH_MARKDOWN_TRIGGER in src/test/mocks/handlers.ts: a message that
 * contains it gets a reply with display math and a fenced code block.
 */
const RICH_MARKDOWN_TRIGGER = "show me rich markdown";

/** Send the trigger in the chat panel; the reply goes through the one markdown renderer. */
async function askForRichMarkdown(page: Page) {
  await startSupportChat(page);
  const input = page.getByTestId("chat-input");
  await input.fill(`Please ${RICH_MARKDOWN_TRIGGER}`);
  await input.press("Enter");
}

const MATRIX: Record<CapabilityId, Record<SurfaceId, Cell>> = {
  katex: {
    "chat-panel": {
      status: "yes",
      check: async (page) => {
        await askForRichMarkdown(page);
        // The KaTeX chunk loads the first time a reply contains $$…$$.
        await expect(page.locator(".katex").first()).toBeVisible({ timeout: 15_000 });
      },
    },
    operator: { status: "no", reason: NO_RICH },
    "workforce-thread": { status: "no", reason: NO_RICH },
  },
  highlight: {
    "chat-panel": {
      status: "yes",
      check: async (page) => {
        await askForRichMarkdown(page);
        await expect(page.locator("code.hljs").first()).toBeVisible({ timeout: 15_000 });
      },
    },
    operator: { status: "no", reason: NO_RICH },
    "workforce-thread": { status: "no", reason: NO_RICH },
  },
  "output-images": {
    "chat-panel": {
      status: "no",
      reason: "the Manager's bubble renders text parts only; image output items arrive with ChatPanel's move onto AgentConversation and Message",
    },
    operator: { status: "no", reason: NO_RICH },
    "workforce-thread": { status: "no", reason: NO_RICH },
  },
  "quick-replies": {
    "chat-panel": {
      status: "yes",
      check: async (page) => {
        await startSupportChat(page);
        // The mock welcome offers four quick replies.
        await expect(page.getByTestId("quick-reply-btn").first()).toBeVisible();
      },
    },
    operator: { status: "no", reason: "the operator chat renders no quick replies" },
    "workforce-thread": { status: "no", reason: "the member thread renders no quick replies" },
  },
  "input-field": {
    "chat-panel": {
      status: "unit",
      reason: "partial support; no MSW agent sends inputField — see components/chat/__tests__ (secret-input-field)",
    },
    operator: { status: "no", reason: "the operator chat ignores inputField" },
    "workforce-thread": { status: "no", reason: "the member thread ignores inputField" },
  },
  "secret-input": {
    "chat-panel": {
      status: "unit",
      reason: "secret-input-field.tsx exists; no MSW agent asks for a secret — covered by its unit tests",
    },
    operator: { status: "no", reason: "the operator chat has no secret input" },
    "workforce-thread": { status: "no", reason: "the member thread has no secret input" },
  },
  history: {
    "chat-panel": {
      status: "yes",
      check: async (page) => {
        await expect(page.getByTestId("history-toggle")).toBeVisible();
      },
    },
    operator: {
      status: "unit",
      reason: "operator-history.tsx needs an activated operator, which MSW does not provide — covered by operator unit tests",
    },
    "workforce-thread": { status: "no", reason: "the member thread has no conversation history" },
  },
  "inline-hitl": {
    "chat-panel": { status: "no", reason: "shows a paused notice only; deciding is on /manage/approvals" },
    operator: {
      status: "unit",
      reason: "approve/reject per tool call needs an activated operator in a paused turn — covered by approval-banner and lib/operator unit tests",
    },
    "workforce-thread": { status: "no", reason: "shows a paused notice and links to approvals; no inline decision" },
  },
  i18n: {
    "chat-panel": i18nCell,
    operator: i18nCell,
    "workforce-thread": i18nCell,
  },
};

for (const [surfaceId, surface] of Object.entries(SURFACES) as [SurfaceId, Surface][]) {
  test.describe(`feature matrix: ${surfaceId}`, () => {
    test.beforeEach(async ({ page }) => {
      // German for every cell: the i18n cell asserts it, and the others must
      // not depend on English copy (they assert on test ids).
      await page.addInitScript(() => localStorage.setItem("i18nextLng", "de"));
      await page.goto(surface.path);
      await surface.ready(page);
    });

    for (const [capability, row] of Object.entries(MATRIX) as [CapabilityId, Record<SurfaceId, Cell>][]) {
      const cell = row[surfaceId];
      const title = `${capability} @surface:${surfaceId} @capability:${capability}`;
      if (cell.status === "yes") {
        test(title, async ({ page }) => {
          await cell.check(page);
        });
      } else if (cell.status === "unit") {
        // eslint-disable-next-line no-restricted-syntax -- a matrix cell, not a hidden failure: MSW has no fixture for it and the reason names the unit tests that cover it
        test.skip(
          title,
          { annotation: { type: "unit-tested", description: cell.reason } },
          () => {},
        );
      } else {
        // eslint-disable-next-line no-restricted-syntax -- a matrix cell recording a capability the surface lacks today; it becomes an assertion when a phase adds it
        test.fixme(
          title,
          { annotation: { type: "unsupported", description: cell.reason } },
          () => {},
        );
      }
    }
  });
}

// The types already demand a cell for every capability and surface; this keeps
// a cast from slipping one past them. Checked at load, not as a test: every
// test in this tier gets the MSW guard fixture, which needs a page.
for (const [capability, row] of Object.entries(MATRIX)) {
  const missing = Object.keys(SURFACES).filter((s) => !(s in row));
  if (missing.length) throw new Error(`feature matrix: ${capability} has no cell for ${missing.join(", ")}`);
}
