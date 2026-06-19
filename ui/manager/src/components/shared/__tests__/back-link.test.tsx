import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
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

  it("renders empty label", () => {
    renderWithProviders(<BackLink to="/groups" label="" />);
    const link = screen.getByTestId("back-to-list");
    expect(link).toBeInTheDocument();
    // link text content should just be the icon (no label text)
    expect(link.textContent?.trim()).toBe("");
  });
});
