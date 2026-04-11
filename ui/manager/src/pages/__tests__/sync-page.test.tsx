import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { SyncPage } from "@/pages/sync-page";

function renderPage() {
  return renderWithProviders(<SyncPage />);
}

describe("SyncPage", () => {
  it("renders page title and connection form", () => {
    renderPage();
    expect(screen.getByText("Agent Sync")).toBeInTheDocument();
    expect(screen.getByTestId("sync-config-panel")).toBeInTheDocument();
  });

  it("renders source URL and auth inputs", () => {
    renderPage();
    expect(screen.getByTestId("sync-url-input")).toBeInTheDocument();
    expect(screen.getByTestId("sync-auth-input")).toBeInTheDocument();
  });

  it("connect button is disabled when URL is empty", () => {
    renderPage();
    const connectBtn = screen.getByTestId("sync-connect-btn");
    expect(connectBtn).toBeDisabled();
  });

  it("connect button enables when URL is entered", async () => {
    renderPage();
    const user = userEvent.setup();
    const urlInput = screen.getByTestId("sync-url-input");

    await user.type(urlInput, "https://staging.eddi.example.com");
    const connectBtn = screen.getByTestId("sync-connect-btn");
    expect(connectBtn).not.toBeDisabled();
  });

  it("shows empty state message before connection", () => {
    renderPage();
    expect(
      screen.getByText("Connect to a source instance to begin syncing agents.")
    ).toBeInTheDocument();
  });

  it("shows agent mapping after connecting", async () => {
    renderPage();
    const user = userEvent.setup();

    // Enter URL and connect
    const urlInput = screen.getByTestId("sync-url-input");
    await user.type(urlInput, "https://staging.eddi.example.com");
    const connectBtn = screen.getByTestId("sync-connect-btn");
    await user.click(connectBtn);

    // Wait for agent mapping to appear
    await waitFor(() => {
      expect(screen.getByText("Agent Mapping")).toBeInTheDocument();
    });

    // Should show remote agents from mock
    expect(screen.getByText("Support Agent")).toBeInTheDocument();
    expect(screen.getByText("FAQ Agent")).toBeInTheDocument();
  });

  it("preview all button appears after connection", async () => {
    renderPage();
    const user = userEvent.setup();

    const urlInput = screen.getByTestId("sync-url-input");
    await user.type(urlInput, "https://staging.eddi.example.com");
    await user.click(screen.getByTestId("sync-connect-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("sync-preview-all")).toBeInTheDocument();
    });
  });

  it("sync selected button appears after connection", async () => {
    renderPage();
    const user = userEvent.setup();

    const urlInput = screen.getByTestId("sync-url-input");
    await user.type(urlInput, "https://staging.eddi.example.com");
    await user.click(screen.getByTestId("sync-connect-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("sync-execute-btn")).toBeInTheDocument();
    });
  });

  it("auth token input toggles visibility", async () => {
    renderPage();
    const user = userEvent.setup();
    const authInput = screen.getByTestId("sync-auth-input") as HTMLInputElement;

    expect(authInput.type).toBe("password");

    // Find the eye toggle button next to the input
    const eyeButton = authInput.closest(".relative")?.querySelector("button");
    expect(eyeButton).toBeTruthy();

    await user.click(eyeButton!);
    expect(authInput.type).toBe("text");

    await user.click(eyeButton!);
    expect(authInput.type).toBe("password");
  });
});
