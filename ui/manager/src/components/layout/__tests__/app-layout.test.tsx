import { describe, it, expect, afterEach } from "vitest";
import { screen, fireEvent, act } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AppLayout } from "../app-layout";

describe("AppLayout", () => {
  const originalWidth = window.innerWidth;

  beforeAll(() => {
    window.HTMLElement.prototype.scrollIntoView = vi.fn();
  });

  afterEach(() => {
    window.innerWidth = originalWidth;
  });

  it("renders desktop layout by default with Sidebar and TopBar", () => {
    renderWithProviders(<AppLayout />);
    
    expect(screen.getByTestId("app-layout")).toBeInTheDocument();
    
    // Sidebar should be present (desktop mode)
    expect(screen.getByTestId("sidebar")).toBeInTheDocument();
    expect(screen.getByText("Skip to main content")).toBeInTheDocument();
  });

  it("renders mobile layout when window width is less than 768px", async () => {
    window.innerWidth = 500;
    // Trigger window resize event
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });

    renderWithProviders(<AppLayout />);

    // In mobile view, desktop sidebar is hidden
    expect(screen.queryByTestId("sidebar")).not.toBeInTheDocument();
    
    // But TopBar menu button is present and can be clicked to open mobile sidebar
    const menuBtn = screen.getByTestId("mobile-menu-toggle");
    expect(menuBtn).toBeInTheDocument();
  });

  it("opens mobile sidebar when clicking menu button and closes on overlay click", async () => {
    const user = userEvent.setup();
    window.innerWidth = 500;
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });

    renderWithProviders(<AppLayout />);
    
    const menuBtn = screen.getByTestId("mobile-menu-toggle");
    await user.click(menuBtn);

    // Sidebar should now be visible
    expect(screen.getByTestId("sidebar")).toBeInTheDocument();
    
    // Clicking the overlay should close the sidebar
    const overlay = screen.getByTestId("sidebar-overlay");
    await user.click(overlay);

    expect(screen.queryByTestId("sidebar")).not.toBeInTheDocument();
  });

  it("closes mobile sidebar when Escape key is pressed", async () => {
    window.innerWidth = 500;
    act(() => {
      window.dispatchEvent(new Event("resize"));
    });

    renderWithProviders(<AppLayout />);
    
    const menuBtn = screen.getByTestId("mobile-menu-toggle");
    await act(async () => {
      fireEvent.click(menuBtn);
    });

    expect(screen.getByTestId("sidebar")).toBeInTheDocument();

    // Trigger keydown escape
    await act(async () => {
      fireEvent.keyDown(document, { key: "Escape", code: "Escape" });
    });

    expect(screen.queryByTestId("sidebar")).not.toBeInTheDocument();
  });
});
