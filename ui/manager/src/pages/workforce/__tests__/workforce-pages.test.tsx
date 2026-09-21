import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderWithProviders, renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
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
    // Clear localStorage so panel-open defaults are predictable
    localStorage.removeItem("workforce-chat-details-panel");
    localStorage.removeItem("workforce-board-config-panel");
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
      // Default is now open (true) — panel visible by default
      expect(toggleBtn).toHaveAttribute("aria-expanded", "true");
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

      // Group name is shown (grp1 = "Product Review Panel") — may appear in
      // both the heading and the config panel (which is open by default).
      const matches = await screen.findAllByText(/Product Review Panel/i);
      expect(matches.length).toBeGreaterThanOrEqual(1);

      // Back link
      const backLink = screen.getByRole("link", { name: /back/i });
      expect(backLink).toBeInTheDocument();
    });

    it("toggles members panel", async () => {
      const user = userEvent.setup();
      renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");

      // Wait for group load — may appear in both heading and config panel
      const matches = await screen.findAllByText(/Product Review Panel/i);
      expect(matches.length).toBeGreaterThanOrEqual(1);

      // Click "Team" toggle — find by aria-label
      const teamBtn = screen.getByRole("button", { name: /team|members/i });
      await user.click(teamBtn);

      // Members panel should now be visible
      await waitFor(() => {
        const panels = screen.getAllByRole("dialog");
        expect(panels.length).toBeGreaterThan(0);
      });
    });

    /**
     * The Sessions and Team slide-overs were `fixed inset-y-0 end-0`, so they
     * sat on top of the right-hand half of the board's own action bar — "+ New"
     * included. With a panel open, the first click on "+ New" landed on the
     * panel; with none open it worked. That is the whole of "the New
     * conversation button doesn't work, sometimes".
     *
     * jsdom has no layout, so the geometry is pinned in `e2e/workforce.spec.ts`.
     * What is checkable here is the containment that makes the geometry
     * possible: the panels must live INSIDE the content row, which begins below
     * the action bar — not as siblings of it.
     */
    it("renders the slide-overs below the action bar, not over it", async () => {
      const user = userEvent.setup();
      renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
      await screen.findAllByText(/Product Review Panel/i);

      await user.click(screen.getByTestId("sessions-toggle"));
      const panel = await screen.findByTestId("sessions-panel");
      // Still a labelled dialog to assistive tech, not merely a div with an id.
      expect(panel).toHaveAttribute("role", "dialog");
      expect(panel).toHaveAccessibleName(/sessions panel/i);

      const newButton = screen.getByTestId("new-discussion-btn");
      // The action bar is outside whatever the panel occupies, so nothing the
      // panel draws can be on top of it.
      expect(panel.contains(newButton)).toBe(false);
      const row = panel.parentElement;
      expect(row).not.toBeNull();
      expect(row!.contains(newButton)).toBe(false);
      // Positioned against the content row rather than the viewport — `fixed`
      // is what put it over the bar.
      expect(panel.className).toContain("absolute");
      expect(panel.className).not.toContain("fixed");
    });

    it("clears the open slide-over when a new discussion is started", async () => {
      const user = userEvent.setup();
      renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
      await screen.findAllByText(/Product Review Panel/i);

      await user.click(screen.getByTestId("sessions-toggle"));
      expect(await screen.findByTestId("sessions-panel")).toBeInTheDocument();

      await user.click(screen.getByTestId("new-discussion-btn"));

      // Whichever panel was open is about the discussion being left behind, so
      // a fresh board must not still be showing it.
      await waitFor(() => {
        expect(screen.queryByTestId("sessions-panel")).not.toBeInTheDocument();
      });
    });

    // The board offered two ways into history — the Sessions slide-over and a
    // link to the full-page history. Only the slide-over remains.
    it("offers exactly one history affordance and no link to the history page", async () => {
      renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");
      await screen.findAllByText(/Product Review Panel/i);

      expect(
        screen.queryByRole("link", { name: /view history/i }),
      ).not.toBeInTheDocument();
      // The Sessions slide-over toggle is the single remaining entry point.
      expect(screen.getByRole("button", { name: "Sessions" })).toBeInTheDocument();
    });

    // Reloading mid-discussion used to land on an empty board with no sign the
    // task force was still working.
    it("picks up a still-running discussion on load and flags it as ongoing", async () => {
      server.use(
        // Scoped to gc-3b so the test proves *that* conversation was restored,
        // not merely that some conversation got selected.
        http.get("*/groups/:groupId/conversations/:convId", ({ params }) => {
          if (params.convId !== "gc-3b") {
            return HttpResponse.json({ error: "unexpected conversation" }, { status: 404 });
          }
          return HttpResponse.json({
            id: "gc-3b",
            groupId: "grp3",
            state: "IN_PROGRESS",
            originalQuestion: "Synthesize competitor analysis data",
            transcript: [
              {
                speakerAgentId: "user",
                speakerDisplayName: "User",
                content: "Synthesize competitor analysis data",
                phaseIndex: -1,
                phaseName: null,
                type: "QUESTION",
                timestamp: new Date().toISOString(),
                errorReason: null,
                targetAgentId: null,
              },
            ],
            synthesizedAnswer: null,
            availableActions: [],
          });
        }),
      );

      // grp3's mock history contains one IN_PROGRESS discussion (gc-3b), and the
      // URL carries no ?conversation= — as after a browser reload.
      renderPage("/workforce/grp3?version=1", <WorkforceBoard />, "/workforce/:boardId");

      expect(await screen.findByTestId("board-live-badge")).toHaveTextContent(/in progress/i);
      expect(await screen.findByTestId("transcript-live-row")).toBeInTheDocument();
    });

    it("shows no ongoing indicator for a settled discussion", async () => {
      // grp1's conversations are all COMPLETED/FAILED — nothing to resume.
      renderPage("/workforce/grp1?version=1", <WorkforceBoard />, "/workforce/:boardId");

      // Anchor on a positive signal that only renders once the conversations
      // query has resolved — a bare absence check could pass before the data
      // that would contradict it ever arrives.
      expect(
        await screen.findByRole("button", { name: /view past sessions/i }),
      ).toBeInTheDocument();
      expect(screen.queryByTestId("board-live-badge")).not.toBeInTheDocument();
    });
  });

  describe("WorkforceWizard", () => {
    it("renders step 1 by default and allows navigation", async () => {
      const user = userEvent.setup();
      renderPage("/workforce/new", <WorkforceWizard />, "/workforce/new");

      // Starts on template step
      expect(await screen.findByText(/Choose a Template|Advisory Board/i)).toBeInTheDocument();

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
      expect(await screen.findByText(/Choose a Template|Advisory Board/i)).toBeInTheDocument();
    });
  });
});
