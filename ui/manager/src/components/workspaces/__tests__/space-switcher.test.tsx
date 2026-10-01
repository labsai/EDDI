import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SpaceSwitcher } from "@/components/workspaces/space-switcher";
import { useSpaces } from "@/hooks/use-spaces";
import * as workspacesApi from "@/lib/api/workspaces";
import type { WorkspaceInfo } from "@/lib/api/workspaces";

const ALICE = "alice@example.com";
const PERSONAL = { id: `user:${ALICE}`, kind: "personal" as const, label: ALICE };
const TEAM = { id: "team:engineering", kind: "team" as const, label: "engineering" };

/**
 * Renders once the workspace query has actually settled.
 *
 * <h3>Why a probe and not a `waitFor`</h3> Both components under test render
 * *nothing* in the cases below, so there is no positive observable to settle on
 * — and `waitFor(() => expect(queryByTestId(x)).not.toBeInTheDocument())`
 * resolves on its FIRST poll, which happens before the query has resolved. Every
 * such assertion therefore passed against a component that had not rendered yet,
 * and would have passed just as happily if the thing appeared a tick later.
 *
 * Awaiting this probe makes "not rendered" mean "not rendered once the answer
 * was in", which is the actual claim.
 */
function SpacesReady() {
  const { isLoading } = useSpaces();
  return isLoading ? null : <div data-testid="spaces-ready" />;
}

function info(overrides: Partial<WorkspaceInfo> = {}): WorkspaceInfo {
  return {
    enabled: true,
    principal: ALICE,
    defaultSpace: PERSONAL.id,
    spaces: [PERSONAL, TEAM],
    seesEverything: false,
    ...overrides,
  };
}

/**
 * A control that hides itself more often than it appears.
 *
 * Every "renders nothing" case here is a deliberate refusal to teach a concept
 * the deployment does not use — and each one would otherwise show a filter that
 * either cannot work or has nothing to offer.
 */
describe("SpaceSwitcher", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    localStorage.clear();
  });

  async function render(ws = info()) {
    vi.spyOn(workspacesApi, "getWorkspaceInfo").mockResolvedValue(ws);
    const result = renderWithProviders(
      <>
        <SpaceSwitcher />
        <SpacesReady />
      </>
    );
    // Settle the query, not merely the fetch call — see SpacesReady.
    await screen.findByTestId("spaces-ready");
    return result;
  }

  it("offers every space the caller can reach, plus the unfiltered view", async () => {
    await render();

    await userEvent.click(await screen.findByTestId("space-switcher"));

    expect(screen.getByTestId("space-option-all")).toBeInTheDocument();
    expect(screen.getByTestId(`space-option-${PERSONAL.id}`)).toBeInTheDocument();
    expect(screen.getByTestId(`space-option-${TEAM.id}`)).toBeInTheDocument();
  });

  it("hides itself when enforcement is off", async () => {
    // Everyone already sees everything, so the control could only ever narrow
    // to a filter the server will not apply.
    await render(info({ enabled: false, seesEverything: true }));

    expect(screen.queryByTestId("space-switcher")).not.toBeInTheDocument();
  });

  it("stays available with a single space, because \"Shared with me\" is still a choice", async () => {
    // It used to hide with one space. A user with no team still receives
    // shares, and the ownership filter is how they find them.
    await render(info({ spaces: [PERSONAL] }));

    await userEvent.click(await screen.findByTestId("space-switcher"));

    expect(screen.getByTestId("ownership-option-shared")).toBeInTheDocument();
  });

  it("filters by ownership and says so on the trigger", async () => {
    await render();

    await userEvent.click(await screen.findByTestId("space-switcher"));
    await userEvent.click(screen.getByTestId("ownership-option-shared"));

    expect(screen.getByTestId("space-switcher")).toHaveTextContent("Shared with me");
    expect(localStorage.getItem("eddi.workspace.ownership")).toBe("shared");

    await userEvent.click(screen.getByTestId("space-switcher"));
    await userEvent.click(screen.getByTestId("ownership-option-any"));
    expect(screen.getByTestId("space-switcher")).not.toHaveTextContent("Shared with me");
  });

  it("says where new items are created, following the workspace in view", async () => {
    await render();

    await userEvent.click(await screen.findByTestId("space-switcher"));
    await userEvent.click(screen.getByTestId(`space-option-${TEAM.id}`));
    await userEvent.click(screen.getByTestId("space-switcher"));

    expect(screen.getByTestId("space-create-hint")).toHaveTextContent("engineering");
  });

  it("calls the personal space the same thing in the trigger as in the menu", async () => {
    // The trigger used the raw label and so showed "alice@example.com" one
    // click after the menu had called it "My workspace" — the same space named
    // two different things.
    await render();

    await userEvent.click(await screen.findByTestId("space-switcher"));
    await userEvent.click(screen.getByTestId(`space-option-${PERSONAL.id}`));

    expect(screen.getByTestId("space-switcher")).toHaveTextContent("My workspace");
    expect(screen.getByTestId("space-switcher")).not.toHaveTextContent(ALICE);
  });

  it("shows a team by its name once chosen", async () => {
    await render();

    await userEvent.click(await screen.findByTestId("space-switcher"));
    await userEvent.click(screen.getByTestId(`space-option-${TEAM.id}`));

    expect(screen.getByTestId("space-switcher")).toHaveTextContent("engineering");
    expect(localStorage.getItem("eddi.workspace.space")).toBe(TEAM.id);
  });

  it("returns to the unfiltered view", async () => {
    await render();

    await userEvent.click(await screen.findByTestId("space-switcher"));
    await userEvent.click(screen.getByTestId(`space-option-${TEAM.id}`));
    await userEvent.click(screen.getByTestId("space-switcher"));
    await userEvent.click(screen.getByTestId("space-option-all"));

    expect(screen.getByTestId("space-switcher")).toHaveTextContent("All workspaces");
    expect(localStorage.getItem("eddi.workspace.space")).toBeNull();
  });
});
