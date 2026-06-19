import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { StreamingToggle } from "@/components/chat/streaming-toggle";

describe("StreamingToggle", () => {
  it("renders the toggle button", () => {
    renderWithProviders(<StreamingToggle />);
    expect(screen.getByTestId("streaming-toggle")).toBeInTheDocument();
  });

  it("has aria-pressed attribute", () => {
    renderWithProviders(<StreamingToggle />);
    const toggle = screen.getByTestId("streaming-toggle");
    expect(toggle).toHaveAttribute("aria-pressed");
  });

  it("has aria-label", () => {
    renderWithProviders(<StreamingToggle />);
    const toggle = screen.getByTestId("streaming-toggle");
    expect(toggle).toHaveAttribute("aria-label");
  });

  it("toggles state on click", async () => {
    const user = userEvent.setup();
    renderWithProviders(<StreamingToggle />);

    const toggle = screen.getByTestId("streaming-toggle");
    const initialState = toggle.getAttribute("aria-pressed");

    await user.click(toggle);

    const newState = toggle.getAttribute("aria-pressed");
    expect(newState).not.toBe(initialState);
  });

  it("shows Zap or ZapOff icon", () => {
    const { container } = renderWithProviders(<StreamingToggle />);
    // One of these icons should be present
    const zap = container.querySelector("svg.lucide-zap");
    const zapOff = container.querySelector("svg.lucide-zap-off");
    expect(zap || zapOff).not.toBeNull();
  });
});
