import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { BackLink } from "@/components/shared/back-link";

describe("BackLink", () => {
  it("renders with the correct label", () => {
    renderWithProviders(<BackLink to="/home" label="Back to Home" />);
    expect(screen.getByText("Back to Home")).toBeInTheDocument();
  });

  it("has correct href attribute", () => {
    renderWithProviders(<BackLink to="/manage/resources" label="Back" />);
    const link = screen.getByTestId("back-to-list");
    expect(link).toHaveAttribute("href", "/manage/resources");
  });

  it("has data-testid back-to-list", () => {
    renderWithProviders(<BackLink to="/test" label="Go Back" />);
    expect(screen.getByTestId("back-to-list")).toBeInTheDocument();
  });

  it("renders the ArrowLeft icon", () => {
    const { container } = renderWithProviders(
      <BackLink to="/test" label="Back" />
    );
    const icon = container.querySelector("svg.lucide-arrow-left");
    expect(icon).not.toBeNull();
  });

  it("hands a plain click to onNavigate instead of navigating", async () => {
    // The seam a page with unsaved edits needs: without it the most prominent
    // exit on the page is the one that discards a draft silently.
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    renderWithProviders(
      <BackLink to="/manage/connections" label="Back" onNavigate={onNavigate} />,
    );

    await user.click(screen.getByTestId("back-to-list"));

    expect(onNavigate).toHaveBeenCalledWith("/manage/connections");
  });

  it("leaves a modified click to the browser", async () => {
    // Ctrl/Cmd-click opens a new tab, which does not navigate *this* page — so
    // there is nothing to guard, and swallowing it would cost the affordance
    // an anchor exists for.
    const user = userEvent.setup();
    const onNavigate = vi.fn();
    renderWithProviders(
      <BackLink to="/manage/connections" label="Back" onNavigate={onNavigate} />,
    );

    await user.keyboard("{Control>}");
    await user.click(screen.getByTestId("back-to-list"));
    await user.keyboard("{/Control}");

    expect(onNavigate).not.toHaveBeenCalled();
  });

  it("stays an ordinary link when no handler is given", async () => {
    const user = userEvent.setup();
    renderWithProviders(<BackLink to="/manage/agents" label="Back" />);

    // No throw, and the href is still the navigation.
    await user.click(screen.getByTestId("back-to-list"));
    expect(screen.getByTestId("back-to-list")).toHaveAttribute(
      "href",
      "/manage/agents",
    );
  });

  it("renders empty label", () => {
    renderWithProviders(<BackLink to="/groups" label="" />);
    const link = screen.getByTestId("back-to-list");
    expect(link).toBeInTheDocument();
    // link text content should just be the icon (no label text)
    expect(link.textContent?.trim()).toBe("");
  });
});
