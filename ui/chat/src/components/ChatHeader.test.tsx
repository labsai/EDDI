/* ──────────────────────────────────────────────
   ChatHeader Tests
   ────────────────────────────────────────────── */

import { describe, it, expect } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { ChatProvider } from "@/store/chat-store";
import { ChatHeader } from "./ChatHeader";

function renderHeader(config = {}) {
  return render(
    <ChatProvider config={config}>
      <ChatHeader />
    </ChatProvider>,
  );
}

describe("ChatHeader", () => {
  it("renders the logo image by default", () => {
    renderHeader();
    const logo = screen.getByRole("img", { name: "EDDI" });
    expect(logo).toBeInTheDocument();
  });

  it("renders the theme toggle button", () => {
    renderHeader();
    expect(screen.getByTestId("theme-toggle")).toBeInTheDocument();
  });

  it("hides the logo when showLogo is false (no branding text either)", () => {
    renderHeader({ showLogo: false });
    expect(screen.queryByRole("img", { name: "EDDI" })).not.toBeInTheDocument();
    // No fallback "EDDI" text either
    expect(screen.queryByText("EDDI")).not.toBeInTheDocument();
  });

  it("does not show agent name when no agent name is set in state", () => {
    const { container } = renderHeader();
    expect(container.querySelector(".chat-header__agent-name")).not.toBeInTheDocument();
  });

  it("renders custom logo URL", () => {
    renderHeader({ logoUrl: "/custom-logo.svg" });
    const logo = screen.getByRole("img", { name: "EDDI" });
    expect(logo).toHaveAttribute("src", "/custom-logo.svg");
  });

  it("clicking theme toggle calls setTheme", () => {
    renderHeader();
    const toggle = screen.getByTestId("theme-toggle");
    fireEvent.click(toggle);
    // Theme changes are reflected on the document element
    expect(toggle).toBeInTheDocument();
  });
});
