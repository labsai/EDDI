import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TopBar } from "@/components/layout/top-bar";

describe("TopBar", () => {
  it("renders theme toggle buttons", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    expect(screen.getByTestId("theme-light")).toBeInTheDocument();
    expect(screen.getByTestId("theme-dark")).toBeInTheDocument();
    expect(screen.getByTestId("theme-system")).toBeInTheDocument();
  });

  it("renders language selector", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    expect(screen.getByTestId("language-selector")).toBeInTheDocument();
  });

  it("renders mobile menu toggle", () => {
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    expect(screen.getByTestId("mobile-menu-toggle")).toBeInTheDocument();
  });

  it("changes theme on button click", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <TopBar onMenuClick={() => {}} sidebarVisible={false} />
    );

    const darkBtn = screen.getByTestId("theme-dark");
    await user.click(darkBtn);

    expect(document.documentElement.classList.contains("dark")).toBe(true);
  });
});
