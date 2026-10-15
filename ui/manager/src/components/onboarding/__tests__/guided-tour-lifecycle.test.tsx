import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useNavigate } from "react-router-dom";
import { GuidedTour } from "@/components/onboarding/guided-tour";
import { useOnboarding } from "@/hooks/use-onboarding";

function Nav() {
  const navigate = useNavigate();
  return <button onClick={() => navigate("/manage/agents")}>go agents</button>;
}

function renderTour() {
  return render(
    <MemoryRouter initialEntries={["/manage"]}>
      <Nav />
      <GuidedTour />
    </MemoryRouter>,
  );
}

function addTarget() {
  const sidebar = document.createElement("div");
  sidebar.setAttribute("data-testid", "sidebar");
  sidebar.appendChild(document.createElement("nav"));
  document.body.appendChild(sidebar);
  return sidebar;
}

describe("GuidedTour lifecycle", () => {
  let target: HTMLElement | null = null;

  beforeEach(() => {
    document.body.style.overflow = "";
    useOnboarding.setState({
      showWelcome: false,
      activeChapter: null,
      currentStep: 0,
      offeredChapter: null,
      completedChapters: new Set(),
    });
  });

  afterEach(() => {
    target?.remove();
    target = null;
    vi.useRealTimers();
  });

  it("does not hijack Enter or lock scroll while its target is not rendered", () => {
    useOnboarding.getState().startChapter("dashboard");
    renderTour();

    expect(screen.queryByTestId("tour-tooltip")).not.toBeInTheDocument();
    expect(document.body.style.overflow).toBe("");

    // The invisible tour used to swallow this and advance a step nobody could see.
    const event = new KeyboardEvent("keydown", { key: "Enter", bubbles: true, cancelable: true });
    document.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(false);
    expect(useOnboarding.getState().currentStep).toBe(0);
  });

  it("attaches its keys and scroll lock once the target appears, and drops them when it goes", async () => {
    useOnboarding.getState().startChapter("dashboard");
    renderTour();
    expect(document.body.style.overflow).toBe("");

    // Late arrival — a lazy-loaded page finishing its render. The MutationObserver
    // in useTargetRect picks it up; no fixed delay is involved.
    target = addTarget();
    expect(await screen.findByTestId("tour-tooltip")).toBeInTheDocument();
    await vi.waitFor(() => expect(document.body.style.overflow).toBe("hidden"));

    target.remove();
    await vi.waitFor(() => expect(screen.queryByTestId("tour-tooltip")).not.toBeInTheDocument());
    expect(document.body.style.overflow).toBe("");
  });

  it("moves focus into the tooltip", async () => {
    target = addTarget();
    useOnboarding.getState().startChapter("dashboard");
    renderTour();

    const tooltip = await screen.findByTestId("tour-tooltip");
    expect(tooltip).toHaveFocus();
  });

  it("Enter on the Back or Skip button does what the button says, not Next", async () => {
    target = addTarget();
    useOnboarding.getState().startChapter("dashboard");
    useOnboarding.setState({ currentStep: 1 });
    // Step 2's target.
    const stats = document.createElement("div");
    stats.setAttribute("data-tour", "dashboard-stats");
    document.body.appendChild(stats);
    renderTour();
    await screen.findByTestId("tour-tooltip");

    const back = screen.getByTestId("tour-back");
    back.focus();
    fireEvent.keyDown(back, { key: "Enter" });
    // The global handler stayed out of it; the button's own click does the work.
    expect(useOnboarding.getState().currentStep).toBe(1);

    await userEvent.setup().click(back);
    expect(useOnboarding.getState().currentStep).toBe(0);
    stats.remove();
  });

  it("Enter with focus elsewhere still advances", async () => {
    target = addTarget();
    useOnboarding.getState().startChapter("dashboard");
    renderTour();
    await screen.findByTestId("tour-tooltip");

    fireEvent.keyDown(document.body, { key: "Enter" });
    expect(useOnboarding.getState().currentStep).toBe(1);
  });

  it("ends the chapter when the user navigates to another page, without marking it done", async () => {
    target = addTarget();
    useOnboarding.getState().startChapter("dashboard");
    renderTour();
    await screen.findByTestId("tour-tooltip");

    await userEvent.setup().click(screen.getByText("go agents"));

    expect(useOnboarding.getState().activeChapter).toBeNull();
    // Abandoned, not completed: the user never saw the rest of it.
    expect(useOnboarding.getState().completedChapters.has("dashboard")).toBe(false);
    expect(document.body.style.overflow).toBe("");
  });

  it("skips a step whose target never appears, and gives up only after the last one", () => {
    vi.useFakeTimers();
    useOnboarding.getState().startChapter("dashboard");
    renderTour();

    // First step (a sidebar target, absent on a phone with the drawer closed):
    // skipped, not fatal to the whole chapter.
    act(() => {
      vi.advanceTimersByTime(8001);
    });
    expect(useOnboarding.getState().activeChapter).toBe("dashboard");
    expect(useOnboarding.getState().currentStep).toBe(1);

    // With every target missing it walks off the end and abandons, not completes.
    for (let i = 0; i < 40 && useOnboarding.getState().activeChapter; i++) {
      act(() => {
        vi.advanceTimersByTime(8001);
      });
    }
    expect(useOnboarding.getState().activeChapter).toBeNull();
    expect(useOnboarding.getState().completedChapters.has("dashboard")).toBe(false);
  });
});
