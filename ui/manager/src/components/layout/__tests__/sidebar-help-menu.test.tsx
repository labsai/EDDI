import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { Sidebar } from "@/components/layout/sidebar";
import { useOnboarding } from "@/hooks/use-onboarding";

describe("Sidebar — Help & Tour menu", () => {
  beforeEach(() => {
    localStorage.clear();
    useOnboarding.setState({
      showWelcome: false,
      activeChapter: null,
      currentStep: 0,
      offeredChapter: null,
      completedChapters: new Set(["dashboard"]),
    });
  });

  it("announces which chapters are done, not just draws a tick", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />);

    await user.click(screen.getByTestId("sidebar-help"));
    const menu = await screen.findByTestId("help-menu-dropdown");

    expect(within(within(menu).getByTestId("help-chapter-dashboard")).getByText("Completed")).toBeInTheDocument();
    expect(within(within(menu).getByTestId("help-chapter-agents")).queryByText("Completed")).toBeNull();
  });

  it("replays a chapter on the current page straight away", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />, { initialRoute: "/manage" });

    await user.click(screen.getByTestId("sidebar-help"));
    await user.click(await screen.findByTestId("help-chapter-dashboard"));

    expect(useOnboarding.getState().activeChapter).toBe("dashboard");
  });

  it("waits for the destination page before starting a chapter on another route", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Sidebar collapsed={false} onToggle={() => {}} />, { initialRoute: "/manage" });

    await user.click(screen.getByTestId("sidebar-help"));
    await user.click(await screen.findByTestId("help-chapter-agents"));

    // No <Routes> in this tree, so the location does change, and the chapter
    // starts once it has — never from a fixed delay.
    await vi.waitFor(() => expect(useOnboarding.getState().activeChapter).toBe("agents"));
  });
});
