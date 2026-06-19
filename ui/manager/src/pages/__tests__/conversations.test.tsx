import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ConversationsPage } from "@/pages/conversations";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

// Mock sonner toast so we can assert on toast calls
vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

function renderConversations() {
  return renderWithProviders(<ConversationsPage />);
}

describe("ConversationsPage", () => {
  it("renders page heading", () => {
    renderConversations();
    expect(screen.getByText("Conversations")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderConversations();
    expect(screen.getByTestId("conversation-search")).toBeInTheDocument();
  });

  it("renders state filter pills", () => {
    renderConversations();
    expect(screen.getByText("All")).toBeInTheDocument();
    expect(screen.getByText("Active")).toBeInTheDocument();
    expect(screen.getByText("Ended")).toBeInTheDocument();
    expect(screen.getByText("Error")).toBeInTheDocument();
  });

  it("shows subtitle text", () => {
    renderConversations();
    expect(
      screen.getByText("View and manage agent conversations")
    ).toBeInTheDocument();
  });

  // --- Data loading ---

  it("renders conversation items after loading", async () => {
    renderConversations();
    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });
  });

  // --- View toggle ---

  it("can switch to list view", async () => {
    renderConversations();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    // Find and click the list view toggle button (data-testid="view-toggle-list")
    const listToggle = screen.getByTestId("view-toggle-list");
    await user.click(listToggle);

    await waitFor(() => {
      expect(screen.getByTestId("conversation-list")).toBeInTheDocument();
    });
  });

  it("renders table headers in list view", async () => {
    renderConversations();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    const listToggle = screen.getByTestId("view-toggle-list");
    await user.click(listToggle);

    await waitFor(() => {
      expect(screen.getByTestId("conversation-list")).toBeInTheDocument();
    });

    // Table headers should be visible
    expect(screen.getByText("Conversation")).toBeInTheDocument();
    expect(screen.getByText("Agent")).toBeInTheDocument();
    expect(screen.getByText("State")).toBeInTheDocument();
  });

  // --- Search ---

  it("allows typing in the search input", async () => {
    renderConversations();
    const user = userEvent.setup();

    const searchInput = screen.getByTestId("conversation-search");
    await user.type(searchInput, "test search");
    expect(searchInput).toHaveValue("test search");
  });

  // --- State filter ---

  it("filters conversations by state when clicking Error filter pill", async () => {
    renderConversations();
    const user = userEvent.setup();

    // Wait for conversations to load
    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    // Count initial cards
    const initialGrid = screen.getByTestId("conversation-grid");
    const initialCardCount = initialGrid.children.length;

    // Click "Error" filter (use getAllByText since Error appears in both pill and state badges)
    const errorFilters = screen.getAllByText("Error");
    // The first one is the filter pill button
    await user.click(errorFilters[0]!);

    // Wait for the filtered results — the MSW handler filters by conversationState
    // Only ERROR conversations should remain, which is fewer than all
    await waitFor(() => {
      const grid = screen.getByTestId("conversation-grid");
      expect(grid.children.length).toBeLessThan(initialCardCount);
    });

    // Verify all visible cards show Error state badges
    const filteredGrid = screen.getByTestId("conversation-grid");
    const errorBadges = within(filteredGrid).getAllByText("Error");
    expect(errorBadges.length).toBeGreaterThan(0);
  });

  it("shows 'In Progress' filter pill", () => {
    renderConversations();
    expect(screen.getByText("In Progress")).toBeInTheDocument();
  });

  // --- Delete flow ---

  it("shows delete button on conversation cards", async () => {
    renderConversations();
    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    // Delete buttons should exist (aria-label)
    const deleteButtons = screen.getAllByLabelText("Delete conversation");
    expect(deleteButtons.length).toBeGreaterThan(0);
  });

  it("opens delete confirmation dialog when delete is clicked", async () => {
    renderConversations();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    const deleteButtons = screen.getAllByLabelText("Delete conversation");
    await user.click(deleteButtons[0]!);

    // Confirmation dialog should appear with the title
    await waitFor(() => {
      expect(
        screen.getByText("Are you sure you want to delete this conversation?")
      ).toBeInTheDocument();
    });
  });

  it("can cancel the delete dialog", async () => {
    renderConversations();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    const deleteButtons = screen.getAllByLabelText("Delete conversation");
    await user.click(deleteButtons[0]!);

    await waitFor(() => {
      expect(screen.getByText("Cancel")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Cancel"));

    // Dialog should close
    await waitFor(() => {
      expect(
        screen.queryByText("Are you sure you want to delete this conversation?")
      ).not.toBeInTheDocument();
    });
  });

  // --- Error state ---

  it("shows error state when API fails", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () => {
        return HttpResponse.json(
          { error: "Server error" },
          { status: 500 }
        );
      })
    );

    renderConversations();

    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  // --- Empty state ---

  it("shows empty state when no conversations exist", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () => {
        return HttpResponse.json([]);
      })
    );

    renderConversations();

    await waitFor(() => {
      expect(
        screen.getByText(/no conversations/i)
      ).toBeInTheDocument();
    });
  });

  it("shows 'no results' when search + empty result", async () => {
    server.use(
      http.get("*/conversationstore/conversations", () => {
        return HttpResponse.json([]);
      })
    );

    renderConversations();
    const user = userEvent.setup();

    const searchInput = screen.getByTestId("conversation-search");
    await user.type(searchInput, "nonexistent");

    await waitFor(() => {
      expect(screen.getByText(/no results/i)).toBeInTheDocument();
    });
  });

  // --- State badges on cards ---

  it("shows state badges on conversation cards", async () => {
    renderConversations();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    // State badges should be visible
    await waitFor(() => {
      const activeBadges = screen.getAllByText("Active");
      // Should have at least one active conversation badge (plus the filter pill)
      expect(activeBadges.length).toBeGreaterThan(1);
    });
  });

  it("shows Ended state badges", async () => {
    renderConversations();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    await waitFor(() => {
      const endedBadges = screen.getAllByText("Ended");
      expect(endedBadges.length).toBeGreaterThan(1);
    });
  });

  // --- Delete in list view ---

  it("shows delete buttons in list view", async () => {
    renderConversations();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    const listToggle = screen.getByTestId("view-toggle-list");
    await user.click(listToggle);

    await waitFor(() => {
      expect(screen.getByTestId("conversation-list")).toBeInTheDocument();
    });

    const deleteButtons = screen.getAllByLabelText("Delete conversation");
    expect(deleteButtons.length).toBeGreaterThan(0);
  });

  // --- StepCountBadge ---

  it("shows step count badges for conversations", async () => {
    renderConversations();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    // Step count badges show "X steps" text
    await waitFor(() => {
      const stepTexts = screen.getAllByText(/steps?$/);
      expect(stepTexts.length).toBeGreaterThan(0);
    });
  });

  // --- Conversation count ---

  it("shows conversation count text", async () => {
    renderConversations();

    await waitFor(() => {
      expect(screen.getByTestId("conversation-grid")).toBeInTheDocument();
    });

    // The count text includes "conversations"
    await waitFor(() => {
      const countTexts = screen.getAllByText(/conversations/i);
      expect(countTexts.length).toBeGreaterThan(0);
    });
  });

  // --- Card grid has items ---

  it("renders multiple conversation cards", async () => {
    renderConversations();

    await waitFor(() => {
      const grid = screen.getByTestId("conversation-grid");
      expect(grid.children.length).toBeGreaterThan(0);
    });
  });

  // --- View toggle component ---

  it("renders the view toggle component", async () => {
    renderConversations();
    expect(screen.getByTestId("view-toggle")).toBeInTheDocument();
  });
});
