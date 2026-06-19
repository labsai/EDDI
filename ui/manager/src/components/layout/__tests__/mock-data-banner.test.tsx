import { describe, it, expect, afterEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { MockDataBanner } from "@/components/layout/mock-data-banner";

describe("MockDataBanner", () => {
  afterEach(() => {
    // Clean up the global mock flag
    delete (window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__;
  });

  it("renders nothing when __EDDI_MOCK_ACTIVE__ is not set", () => {
    const { container } = renderWithProviders(<MockDataBanner />);
    expect(container.innerHTML).toBe("");
  });

  it("renders banner when __EDDI_MOCK_ACTIVE__ is true", () => {
    (window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__ = true;
    renderWithProviders(<MockDataBanner />);
    expect(
      screen.getByText(
        "Demo Mode — Displaying sample data. Connect an EDDI backend for real data."
      )
    ).toBeInTheDocument();
  });

  it("dismisses banner when close button is clicked", async () => {
    (window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__ = true;
    const user = userEvent.setup();
    renderWithProviders(<MockDataBanner />);

    const dismissBtn = screen.getByLabelText("Dismiss");
    await user.click(dismissBtn);

    expect(
      screen.queryByText(
        "Demo Mode — Displaying sample data. Connect an EDDI backend for real data."
      )
    ).not.toBeInTheDocument();
  });

  it("renders nothing when hideMockBanner query param is true", () => {
    (window as unknown as Record<string, unknown>).__EDDI_MOCK_ACTIVE__ = true;
    // Set the query param
    const originalSearch = window.location.search;
    Object.defineProperty(window, "location", {
      writable: true,
      value: { ...window.location, search: "?hideMockBanner=true" },
    });

    const { container } = renderWithProviders(<MockDataBanner />);
    expect(container.innerHTML).toBe("");

    // Restore
    Object.defineProperty(window, "location", {
      writable: true,
      value: { ...window.location, search: originalSearch },
    });
  });
});
