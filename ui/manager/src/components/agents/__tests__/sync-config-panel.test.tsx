import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SyncConfigPanel } from "@/components/agents/sync-config-panel";

describe("SyncConfigPanel", () => {
  const defaultProps = {
    url: "",
    auth: "",
    onUrlChange: vi.fn(),
    onAuthChange: vi.fn(),
    onConnected: vi.fn(),
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid sync-config-panel", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(screen.getByTestId("sync-config-panel")).toBeInTheDocument();
  });

  it("renders URL input", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(screen.getByTestId("sync-url-input")).toBeInTheDocument();
  });

  it("renders auth input", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(screen.getByTestId("sync-auth-input")).toBeInTheDocument();
  });

  it("renders connect button", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(screen.getByTestId("sync-connect-btn")).toBeInTheDocument();
    expect(screen.getByText("Connect")).toBeInTheDocument();
  });

  it("shows source URL label", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(screen.getByText("Source EDDI Instance URL")).toBeInTheDocument();
  });

  it("shows auth token label", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(screen.getByText("Authorization Token")).toBeInTheDocument();
  });

  it("shows auth hint text", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(
      screen.getByText("Optional. Sent as X-Source-Authorization header.")
    ).toBeInTheDocument();
  });

  it("disables connect button when URL is empty", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(screen.getByTestId("sync-connect-btn")).toBeDisabled();
  });

  it("enables connect button when URL has value", () => {
    renderWithProviders(
      <SyncConfigPanel {...defaultProps} url="https://staging.example.com" />
    );
    expect(screen.getByTestId("sync-connect-btn")).not.toBeDisabled();
  });

  it("calls onUrlChange when URL is typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    const input = screen.getByTestId("sync-url-input");
    await user.type(input, "h");
    expect(defaultProps.onUrlChange).toHaveBeenCalled();
  });

  it("calls onAuthChange when auth is typed", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    const input = screen.getByTestId("sync-auth-input");
    await user.type(input, "B");
    expect(defaultProps.onAuthChange).toHaveBeenCalled();
  });

  it("auth input defaults to password type", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(screen.getByTestId("sync-auth-input")).toHaveAttribute("type", "password");
  });

  it("toggles auth visibility", async () => {
    const user = userEvent.setup();
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    const authInput = screen.getByTestId("sync-auth-input");
    expect(authInput).toHaveAttribute("type", "password");

    // Click eye toggle - find the button near the auth input
    const toggleButtons = screen.getByTestId("sync-auth-input")
      .closest(".relative")!
      .querySelector("button") as HTMLButtonElement;
    await user.click(toggleButtons);
    expect(authInput).toHaveAttribute("type", "text");

    await user.click(toggleButtons);
    expect(authInput).toHaveAttribute("type", "password");
  });

  it("shows URL placeholder", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(
      screen.getByPlaceholderText("https://staging.eddi.example.com")
    ).toBeInTheDocument();
  });

  it("shows auth placeholder", () => {
    renderWithProviders(<SyncConfigPanel {...defaultProps} />);
    expect(
      screen.getByPlaceholderText("Bearer eyJhb...")
    ).toBeInTheDocument();
  });
});
