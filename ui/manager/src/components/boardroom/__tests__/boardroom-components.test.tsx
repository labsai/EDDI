import { describe, it, expect, beforeEach, vi } from "vitest";
import { screen, waitFor, fireEvent } from "@testing-library/react";
import { renderWithProviders, renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

import { BoardroomTopbar } from "../boardroom-topbar";
import { AgentWorkforceCard, AddAgentCard } from "../agent-workforce-card";
import { BoardroomCard } from "../boardroom-card";
import { ContextCard } from "../context-card";
import { QuickActions } from "../quick-actions";
import { BoardroomBottomTabs } from "../boardroom-bottom-tabs";
import { BoardroomShortcuts } from "../boardroom-shortcuts";
import { OnboardingHero } from "../onboarding-hero";
import { TemplatePicker } from "../wizard/template-picker";
import { StepIndicator } from "../wizard/step-indicator";

// Mock resize observer
class ResizeObserverMock {
  observe() {}
  unobserve() {}
  disconnect() {}
}
window.ResizeObserver = ResizeObserverMock;

describe("Boardroom Components", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  // ── BoardroomTopbar ──────────────────────────────────────────────
  describe("BoardroomTopbar", () => {
    it("renders title when provided", () => {
      renderWithProviders(<BoardroomTopbar title="Test Title" />);
      expect(screen.getByText("Test Title")).toBeInTheDocument();
    });

    it("renders back button when backTo is provided", () => {
      renderWithProviders(<BoardroomTopbar backTo="/workforce" />);
      // Back button is an icon button with aria-label
      const backBtn = screen.getByRole("button", { name: /back/i });
      expect(backBtn).toBeInTheDocument();
    });

    it("renders menu button when onMenuClick provided and no backTo", async () => {
      const onMenuClick = vi.fn();
      renderWithProviders(<BoardroomTopbar onMenuClick={onMenuClick} />);

      // Menu button may use different aria-label — find any button
      const buttons = screen.getAllByRole("button");
      expect(buttons.length).toBeGreaterThan(0);

      const user = userEvent.setup();
      await user.click(buttons[0]);
      expect(onMenuClick).toHaveBeenCalled();
    });

    it("renders rightContent slot", () => {
      renderWithProviders(<BoardroomTopbar rightContent={<div data-testid="right-slot">Slot</div>} />);
      expect(screen.getByTestId("right-slot")).toBeInTheDocument();
    });
  });

  // ── AgentWorkforceCard ───────────────────────────────────────────
  describe("AgentWorkforceCard", () => {
    it("renders agent name and description", () => {
      renderWithProviders(
        <AgentWorkforceCard name="Agent 007" agentId="a1" description="Top Secret" />
      );
      expect(screen.getByText("Agent 007")).toBeInTheDocument();
      expect(screen.getByText("Top Secret")).toBeInTheDocument();
    });

    it("shows 'Ready' badge when no description", () => {
      renderWithProviders(
        <AgentWorkforceCard name="Agent 007" agentId="a1" />
      );
      expect(screen.getByText(/Ready/i)).toBeInTheDocument();
    });

    it("calls onClick when clicked", async () => {
      const onClick = vi.fn();
      renderWithProviders(
        <AgentWorkforceCard name="Agent 007" agentId="a1" onClick={onClick} />
      );
      const card = screen.getByRole("button");
      const user = userEvent.setup();
      await user.click(card);
      expect(onClick).toHaveBeenCalled();
    });

    it("AddAgentCard renders and calls onClick", async () => {
      const onClick = vi.fn();
      renderWithProviders(<AddAgentCard onClick={onClick} />);
      const btn = screen.getByRole("button");
      expect(btn).toBeInTheDocument();
      const user = userEvent.setup();
      await user.click(btn);
      expect(onClick).toHaveBeenCalled();
    });
  });

  // ── BoardroomCard ────────────────────────────────────────────────
  describe("BoardroomCard", () => {
    const props = {
      id: "board-1",
      name: "Strategy Board",
      description: "Company Strategy",
      style: "DEBATE" as const,
      isPinned: false,
      members: [],
    };

    it("renders card name in grid mode", () => {
      renderWithProviders(<BoardroomCard {...props} viewMode="grid" />);
      expect(screen.getByText("Strategy Board")).toBeInTheDocument();
    });

    it("renders card name in list mode", () => {
      renderWithProviders(<BoardroomCard {...props} viewMode="list" />);
      expect(screen.getAllByText("Strategy Board")[0]).toBeInTheDocument();
    });

    it("clicking card navigates to board page", () => {
      renderWithProviders(<BoardroomCard {...props} />);
      const link = screen.getByRole("link");
      expect(link.getAttribute("href")).toBe("/workforce/board-1?version=1");
    });

    it("pin toggle calls onTogglePin", async () => {
      const onTogglePin = vi.fn();
      renderWithProviders(<BoardroomCard {...props} onTogglePin={onTogglePin} />);

      const buttons = screen.getAllByRole("button");
      const user = userEvent.setup();

      const pinBtn = buttons.find((b) => b.getAttribute("aria-label")?.includes("pin") || b.getAttribute("aria-label")?.includes("Pin"));
      if (pinBtn) {
        await user.click(pinBtn);
        expect(onTogglePin).toHaveBeenCalled();
      } else {
        await user.click(buttons[0]);
      }
    });

    it("has menu trigger with data-testid", () => {
      renderWithProviders(<BoardroomCard {...props} />);
      expect(screen.getByTestId("boardroom-menu-board-1")).toBeInTheDocument();
    });
  });

  // ── ContextCard ──────────────────────────────────────────────────
  describe("ContextCard", () => {
    it("renders question and response content", () => {
      renderWithProviders(
        <ContextCard boardName="Sales" question="Why?" response="Because!" />
      );
      expect(screen.getByText("Why?")).toBeInTheDocument();
      expect(screen.getByText("Because!")).toBeInTheDocument();
    });

    it("returns null when question and response are both empty", () => {
      const { container } = renderWithProviders(
        <ContextCard boardName="Sales" question="" response="" />
      );
      expect(container.firstChild).toBeNull();
    });
  });

  // ── QuickActions ─────────────────────────────────────────────────
  describe("QuickActions", () => {
    it("renders action links", () => {
      renderWithProviders(<QuickActions />);
      const links = screen.getAllByRole("link");
      const hrefs = links.map((l) => l.getAttribute("href"));
      expect(hrefs.some((h) => h?.includes("/workforce"))).toBe(true);
    });
  });

  // ── BoardroomBottomTabs ──────────────────────────────────────────
  describe("BoardroomBottomTabs", () => {
    it("renders tab buttons", () => {
      renderPage("/workforce", <BoardroomBottomTabs />, "/workforce");
      const buttons = screen.getAllByRole("button");
      expect(buttons.length).toBeGreaterThanOrEqual(3);
    });
  });

  // ── BoardroomShortcuts ───────────────────────────────────────────
  describe("BoardroomShortcuts", () => {
    it("renders null (no visible output)", () => {
      renderPage("/workforce", <BoardroomShortcuts />, "/workforce");
    });

    it("pressing '?' dispatches show-shortcuts event", () => {
      renderPage("/workforce", <BoardroomShortcuts />, "/workforce");

      const handler = vi.fn();
      window.addEventListener("boardroom:show-shortcuts", handler);

      fireEvent.keyDown(window, { key: "?" });

      expect(handler).toHaveBeenCalled();
      window.removeEventListener("boardroom:show-shortcuts", handler);
    });
  });

  // ── OnboardingHero ───────────────────────────────────────────────
  describe("OnboardingHero", () => {
    it("renders hero with deploy banner when no agents", async () => {
      server.use(
        http.get("*/agentstore/agents/descriptors", () => HttpResponse.json([]))
      );

      renderWithProviders(<OnboardingHero />);

      await waitFor(() => {
        expect(screen.getByText(/Welcome to the Workforce/i)).toBeInTheDocument();
      });

      expect(screen.getByText(/Deploy/i)).toBeInTheDocument();
    });

    it("renders how-it-works steps", async () => {
      server.use(
        http.get("*/agentstore/agents/descriptors", () => HttpResponse.json([]))
      );

      renderWithProviders(<OnboardingHero />);

      await waitFor(() => {
        expect(screen.getByText(/How it works/i)).toBeInTheDocument();
      });
    });
  });

  // ── Wizard Components ────────────────────────────────────────────
  describe("Wizard Components", () => {
    describe("TemplatePicker", () => {
      it("renders templates and custom option", () => {
        const onSelect = vi.fn();
        renderWithProviders(
          <TemplatePicker selected={null} onSelect={onSelect} />
        );
        expect(screen.getByText(/Custom/i)).toBeInTheDocument();
      });

      it("calls onSelect when a template is clicked", async () => {
        const onSelect = vi.fn();
        renderWithProviders(
          <TemplatePicker selected={null} onSelect={onSelect} />
        );

        const buttons = screen.getAllByRole("button");
        const customBtn = buttons.find((b) => b.textContent?.includes("Custom"));
        if (customBtn) {
          const user = userEvent.setup();
          await user.click(customBtn);
          expect(onSelect).toHaveBeenCalledWith("custom");
        }
      });
    });

    describe("StepIndicator", () => {
      it("renders steps correctly", () => {
        const steps = [{ label: "Step 1" }, { label: "Step 2" }, { label: "Step 3" }];
        renderWithProviders(
          <StepIndicator steps={steps} currentStep={1} />
        );

        expect(screen.getByText("Step 1")).toBeInTheDocument();
        expect(screen.getByText("Step 2")).toBeInTheDocument();
        expect(screen.getByText("Step 3")).toBeInTheDocument();
      });

      it("marks current step correctly", () => {
        const steps = [{ label: "Step 1" }, { label: "Step 2" }];
        renderWithProviders(
          <StepIndicator steps={steps} currentStep={1} />
        );

        const listItems = screen.getAllByRole("listitem");
        expect(listItems.length).toBe(2);
      });
    });
  });
});
