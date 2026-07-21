import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, renderPage, userEvent } from "@/test/test-utils";
import { useChatStore } from "@/hooks/use-chat";

import { WorkforceChat } from "../workforce-chat";
import { WorkforceDashboard } from "../workforce-dashboard";
import { WorkforceBoard } from "../workforce-board";
import { WorkforceWizard } from "../workforce-wizard";

// Mock resize observer
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

describe("Workforce Pages", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useChatStore.setState({ selectedAgentId: null, selectedAgentName: null });
  });

  describe("WorkforceChat", () => {
    it("renders back link to /workforce", () => {
      renderWithProviders(<WorkforceChat />);

      // Back link uses aria-label="Back" and points to /workforce
      const backLink = screen.getByRole("link", { name: /Back/i });
      expect(backLink).toBeInTheDocument();
      expect(backLink).toHaveAttribute("href", "/workforce");
    });

    it("shows details toggle when agent is selected", () => {
      // Setup selected agent in store
      useChatStore.setState({ selectedAgentId: "agent1", selectedAgentName: "Support Agent" });

      renderWithProviders(<WorkforceChat />);

      // Toggle button renders when agent is selected (aria-label contains 'agent details')
      const toggleBtn = screen.getByRole("button", { name: /agent details/i });
      expect(toggleBtn).toBeInTheDocument();
      expect(toggleBtn).toHaveAttribute("aria-expanded", "false");
    });
  });

  describe("WorkforceDashboard", () => {
    it("renders group cards after data loads", async () => {
      renderPage("/workforce", <WorkforceDashboard />, "/workforce");

      // Wait for group data to load (grp1 = "Product Review Panel")
      expect(await screen.findByText(/Product Review Panel/i)).toBeInTheDocument();
    });

    it("renders view toggle buttons", async () => {
      renderPage("/workforce", <WorkforceDashboard />, "/workforce");

      await waitFor(() => {
        const buttons = screen.getAllByRole("button");
        expect(buttons.length).toBeGreaterThan(0);
      });
    });
  });

  describe("WorkforceBoard", () => {
    it("renders board content after group data loads", async () => {
      // "grp1" is typically provided by the mock server
      renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");

      // Group name is shown (grp1 = "Product Review Panel")
      expect(await screen.findByText(/Product Review Panel/i)).toBeInTheDocument();

      // Back link
      const backLink = screen.getByRole("link", { name: /back/i });
      expect(backLink).toBeInTheDocument();
    });

    it("toggles members panel", async () => {
      const user = userEvent.setup();
      renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");

      // Wait for group load
      expect(await screen.findByText(/Product Review Panel/i)).toBeInTheDocument();

      // Click "Team" toggle — find by aria-label
      const teamBtn = screen.getByRole("button", { name: /team|members/i });
      await user.click(teamBtn);

      // Members panel should now be visible
      await waitFor(() => {
        const panels = screen.getAllByRole("dialog");
        expect(panels.length).toBeGreaterThan(0);
      });
    });
  });

  describe("WorkforceWizard", () => {
    it("renders step 1 by default and allows navigation", async () => {
      const user = userEvent.setup();
      renderPage("/workforce/new", <WorkforceWizard />, "/workforce/new");

      // Starts on template step
      expect(await screen.findByText(/Choose a Template|Strategic Advisory Council/i)).toBeInTheDocument();

      // Find the Next button
      const nextBtn = screen.getByRole("button", { name: /next/i });
      expect(nextBtn).toBeDisabled(); // Disabled until template is selected

      // Select a template (e.g. Brainstorming or Custom)
      const customTemplate = await screen.findByRole("button", { name: /Custom/i });
      await user.click(customTemplate);

      // Now Next should be enabled
      expect(nextBtn).not.toBeDisabled();

      // Go to Step 2
      await user.click(nextBtn);

      // Step 2: Team builder
      expect(await screen.findByText(/Task force name|Workforce name/i)).toBeInTheDocument();

      // Back button should work
      const backBtn = screen.getByRole("button", { name: /back/i });
      await user.click(backBtn);

      // Back to Step 1
      expect(await screen.findByText(/Choose a Template|Strategic Advisory Council/i)).toBeInTheDocument();
    });
  });
});
