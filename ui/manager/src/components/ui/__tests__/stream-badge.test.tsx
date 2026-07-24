import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { StreamBadge } from "@/components/ui/stream-badge";

describe("StreamBadge", () => {
  it("shows 'Live' when connected", () => {
    renderWithProviders(<StreamBadge connected />);
    expect(screen.getByText("Live")).toBeInTheDocument();
  });

  it("shows 'Reconnecting…' when disconnected", () => {
    renderWithProviders(<StreamBadge connected={false} />);
    expect(screen.getByText("Reconnecting…")).toBeInTheDocument();
  });

  it("has a status role for accessibility", () => {
    renderWithProviders(<StreamBadge connected />);
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("applies custom className", () => {
    renderWithProviders(<StreamBadge connected className="extra-class" />);
    const badge = screen.getByTestId("stream-badge");
    expect(badge.className).toContain("extra-class");
  });
});
