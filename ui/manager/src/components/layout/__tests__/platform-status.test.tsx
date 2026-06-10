import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { PlatformStatus } from "@/components/layout/platform-status";

// Mock the hook
vi.mock("@/hooks/use-platform-status", () => ({
  usePlatformStatus: vi.fn(),
}));

import { usePlatformStatus } from "@/hooks/use-platform-status";
const mockUsePlatformStatus = vi.mocked(usePlatformStatus);

describe("PlatformStatus", () => {
  it("shows 'Online' when status is online", () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: "abc123",
      latencyMs: 42,
      lastCheckedAt: new Date(),
    });
    renderWithProviders(<PlatformStatus />);
    expect(screen.getByText("Online")).toBeInTheDocument();
  });

  it("shows 'Offline' when status is offline", () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "offline",
      instanceId: null,
      latencyMs: null,
      lastCheckedAt: new Date(),
    });
    renderWithProviders(<PlatformStatus />);
    expect(screen.getByText("Offline")).toBeInTheDocument();
  });

  it("shows '…' when status is checking", () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "checking",
      instanceId: null,
      latencyMs: null,
      lastCheckedAt: null,
    });
    renderWithProviders(<PlatformStatus />);
    expect(screen.getByText("…")).toBeInTheDocument();
  });

  it("has data-testid platform-status", () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: "abc123",
      latencyMs: 42,
      lastCheckedAt: new Date(),
    });
    renderWithProviders(<PlatformStatus />);
    expect(screen.getByTestId("platform-status")).toBeInTheDocument();
  });

  it("opens popover on click", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: "abc123def456",
      latencyMs: 42,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("Online"));
    expect(screen.getByTestId("platform-status-popover")).toBeInTheDocument();
    expect(screen.getByText("Platform Status")).toBeInTheDocument();
    expect(screen.getByText("Connected")).toBeInTheDocument();
  });

  it("shows instance ID in popover", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: "abc123def456ghi",
      latencyMs: 42,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("Online"));
    expect(screen.getByText("Instance")).toBeInTheDocument();
    // Long IDs get truncated to 12 chars
    expect(screen.getByText("abc123def456…")).toBeInTheDocument();
  });

  it("shows short instance ID without truncation", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: "short",
      latencyMs: 42,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("Online"));
    expect(screen.getByText("short")).toBeInTheDocument();
  });

  it("shows latency in popover", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: "abc",
      latencyMs: 85,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("Online"));
    expect(screen.getByText("Latency")).toBeInTheDocument();
    expect(screen.getByText("85ms")).toBeInTheDocument();
  });

  it("does not show latency when null", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "offline",
      instanceId: null,
      latencyMs: null,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("Offline"));
    expect(screen.queryByText("Latency")).not.toBeInTheDocument();
  });

  it("does not show instance when null", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "offline",
      instanceId: null,
      latencyMs: null,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("Offline"));
    expect(screen.queryByText("Instance")).not.toBeInTheDocument();
  });

  it("shows 'Disconnected' in popover when offline", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "offline",
      instanceId: null,
      latencyMs: null,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("Offline"));
    expect(screen.getByText("Disconnected")).toBeInTheDocument();
  });

  it("shows 'Checking connection…' in popover when checking", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "checking",
      instanceId: null,
      latencyMs: null,
      lastCheckedAt: null,
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("…"));
    expect(screen.getByText("Checking connection…")).toBeInTheDocument();
  });

  it("closes popover on second click", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: "abc",
      latencyMs: 42,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    // Open
    await user.click(screen.getByText("Online"));
    expect(screen.getByTestId("platform-status-popover")).toBeInTheDocument();

    // Close
    await user.click(screen.getByText("Online"));
    expect(screen.queryByTestId("platform-status-popover")).not.toBeInTheDocument();
  });

  it("has proper aria-expanded attribute", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: "abc",
      latencyMs: 42,
      lastCheckedAt: new Date(),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    const button = screen.getByText("Online").closest("button")!;
    expect(button).toHaveAttribute("aria-expanded", "false");

    await user.click(button);
    expect(button).toHaveAttribute("aria-expanded", "true");
  });

  it("has aria-label for online state", () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: null,
      latencyMs: null,
      lastCheckedAt: null,
    });
    renderWithProviders(<PlatformStatus />);
    const button = screen.getByText("Online").closest("button")!;
    expect(button).toHaveAttribute("aria-label", "Online");
  });

  it("shows last checked info in popover", async () => {
    mockUsePlatformStatus.mockReturnValue({
      status: "online",
      instanceId: null,
      latencyMs: null,
      lastCheckedAt: new Date(Date.now() - 2000),
    });
    const user = userEvent.setup();
    renderWithProviders(<PlatformStatus />);

    await user.click(screen.getByText("Online"));
    expect(screen.getByText("Last checked")).toBeInTheDocument();
  });
});
