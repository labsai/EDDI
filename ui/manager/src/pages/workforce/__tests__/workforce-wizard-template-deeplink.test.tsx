import { describe, it, expect, beforeEach } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderPage } from "@/test/test-utils";
import { WorkforceWizard } from "@/pages/workforce/workforce-wizard";

/**
 * Three places link into the wizard with a template already chosen:
 *   onboarding-hero.tsx  -> /workforce/new?template=<builtin key>
 *   onboarding-hero.tsx  -> /workforce/new?template=custom
 *   workforce-dashboard  -> /workforce/new?template=<saved template UUID>
 *
 * The wizard read no search params at all, so every one of them landed on step 0
 * with nothing selected and Next disabled — the user's choice discarded. Note the
 * two id spaces: built-ins are keyed by `key`, saved templates by a
 * crypto.randomUUID() `id` held in localStorage.
 */

class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

const STORAGE_KEY = "workforce-templates";

function renderWizard(search: string) {
  return renderPage(`/workforce/new${search}`, <WorkforceWizard />, "/workforce/new");
}

/** The Next button, once rendered. Disabled until step 0 has a selection. */
async function nextButton() {
  return await waitFor(() => screen.getByRole("button", { name: /next|weiter/i }));
}

/** Await the button, then assert on it directly — nesting `expect(...).resolves`
 *  inside another `waitFor` retries until timeout and makes a real failure take
 *  10s to report. */
async function expectNext(state: "enabled" | "disabled") {
  const btn = await nextButton();
  await waitFor(() =>
    state === "enabled"
      ? expect(btn).toBeEnabled()
      : expect(btn).toBeDisabled(),
  );
}

beforeEach(() => {
  localStorage.removeItem(STORAGE_KEY);
});

describe("wizard ?template= deep link", () => {
  it("applies a built-in template and enables Next", async () => {
    renderWizard("?template=advisory-board");
    await expectNext("enabled");
  });

  it("applies the custom template and enables Next", async () => {
    renderWizard("?template=custom");
    await expectNext("enabled");
  });

  it("applies a saved template addressed by its UUID", async () => {
    const saved = {
      id: "11111111-2222-3333-4444-555555555555",
      name: "My saved force",
      description: "from localStorage",
      style: "ROUND_TABLE",
      members: [
        { displayName: "Ana", role: "Research" },
        { displayName: "Bo", role: "Legal" },
      ],
      maxRounds: 3,
      createdAt: new Date(0).toISOString(),
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify([saved]));

    renderWizard(`?template=${saved.id}`);
    await expectNext("enabled");
  });

  it("a manual pick clears the deep-linked saved template's style", async () => {
    // savedTemplateConfig is only consulted for the "custom" build path, so if a
    // manual selection did not clear it, arriving via a saved template and then
    // choosing Custom would silently create the group with the saved template's
    // style and round count instead of the CUSTOM/1 defaults.
    const saved = {
      id: "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
      name: "Deep linked",
      description: "has its own style",
      style: "ROUND_TABLE",
      members: [{ displayName: "Ana", role: "Research" }],
      maxRounds: 5,
      createdAt: new Date(0).toISOString(),
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify([saved]));

    renderWizard(`?template=${saved.id}`);
    await expectNext("enabled");

    // Pick a built-in card manually, then advance to the Team step where the
    // board name is editable. handleTemplateSelect is what clears
    // savedTemplateConfig, so seeing its other effect (the name switching to the
    // built-in template's) confirms the handler ran on a manual pick — the path
    // that used to leave the deep-linked style in place.
    fireEvent.click(screen.getByText(/advisory board/i));
    fireEvent.click(await nextButton());

    await waitFor(() =>
      expect(screen.queryByDisplayValue("Deep linked")).toBeNull(),
    );
  });

  it("leaves the picker untouched for an unknown template id", async () => {
    renderWizard("?template=does-not-exist");
    // Still on step 0 with no selection, so Next stays disabled — better than
    // half-applying a template that could not be resolved.
    await expectNext("disabled");
  });

  it("with no parameter, Next starts disabled", async () => {
    renderWizard("");
    await expectNext("disabled");
  });
});
