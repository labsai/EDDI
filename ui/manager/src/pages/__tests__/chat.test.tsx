import { describe, it, expect } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { ChatPage } from "@/pages/chat";

describe("ChatPage", () => {
  it("renders page heading", () => {
    renderWithProviders(<ChatPage />);
    expect(
      screen.getByRole("heading", { level: 1, name: /chat/i })
    ).toBeInTheDocument();
  });

  it("renders page subtitle", () => {
    renderWithProviders(<ChatPage />);
    expect(
      screen.getByText("Talk to your deployed agents")
    ).toBeInTheDocument();
  });

  it("renders agent selector", () => {
    renderWithProviders(<ChatPage />);
    expect(screen.getByTestId("agent-selector")).toBeInTheDocument();
  });

  it("renders chat input", () => {
    renderWithProviders(<ChatPage />);
    expect(screen.getByTestId("chat-input")).toBeInTheDocument();
  });

  it("renders streaming toggle", () => {
    renderWithProviders(<ChatPage />);
    expect(screen.getByTestId("streaming-toggle")).toBeInTheDocument();
  });

  it("renders history toggle button", () => {
    renderWithProviders(<ChatPage />);
    expect(screen.getByTestId("history-toggle")).toBeInTheDocument();
  });

  it("shows empty state when no agent selected", () => {
    renderWithProviders(<ChatPage />);
    expect(
      screen.getByText("Select an agent and start chatting!")
    ).toBeInTheDocument();
  });
});
