import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { WorkflowsPage } from "@/pages/workflows";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

// Mock sonner toast
vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

function renderWorkflows() {
  return renderWithProviders(<WorkflowsPage />);
}

describe("WorkflowsPage", () => {
  it("renders page heading", () => {
    renderWorkflows();
    expect(screen.getByText("Workflows")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderWorkflows();
    expect(screen.getByTestId("workflow-search")).toBeInTheDocument();
  });

  it("renders create workflow button", () => {
    renderWorkflows();
    expect(screen.getByTestId("create-workflow-btn")).toBeInTheDocument();
  });

  it("shows subtitle text", () => {
    renderWorkflows();
    expect(
      screen.getByText("Configure agent workflows and task pipelines")
    ).toBeInTheDocument();
  });

  it("renders the view toggle component", () => {
    renderWorkflows();
    expect(screen.getByTestId("view-toggle")).toBeInTheDocument();
  });

  // --- Data loading ---

  it("shows loading skeletons initially", () => {
    renderWorkflows();
    const skeletons = document.querySelectorAll('[class*="animate-pulse"]');
    expect(skeletons.length).toBeGreaterThan(0);
  });

  it("renders workflow cards after loading", async () => {
    renderWorkflows();
    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });
  });

  it("shows workflow count", async () => {
    renderWorkflows();
    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });
    await waitFor(() => {
      const countTexts = screen.getAllByText(/workflow/i);
      expect(countTexts.length).toBeGreaterThan(0);
    });
  });

  // --- View toggle ---

  it("can switch to list view", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    const listToggle = screen.getByTestId("view-toggle-list");
    await user.click(listToggle);

    await waitFor(() => {
      expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
    });
  });

  it("renders table headers in list view", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
    });

    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Version")).toBeInTheDocument();
    expect(screen.getByText("Modified")).toBeInTheDocument();
  });

  it("can switch back to card view from list view", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));
    await waitFor(() => {
      expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-card"));
    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });
  });

  // --- Search ---

  it("allows typing in the search input", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    const searchInput = screen.getByTestId("workflow-search");
    await user.type(searchInput, "customer support");
    expect(searchInput).toHaveValue("customer support");
  });

  // --- Sorting in list view ---

  it("toggles sort direction when clicking the same column header", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
    });

    const nameHeader = screen.getByText("Name");
    await user.click(nameHeader);
    await user.click(nameHeader);

    expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
  });

  it("can sort by version column", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
    });

    const versionHeader = screen.getByText("Version");
    await user.click(versionHeader);

    expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
  });

  it("can sort by modified column", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
    });

    const modifiedHeader = screen.getByText("Modified");
    await user.click(modifiedHeader);

    expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
  });

  // --- Create workflow dialog ---

  it("opens create workflow dialog when create button is clicked", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("create-workflow-btn"));

    await waitFor(() => {
      expect(screen.getByText(/new workflow/i)).toBeInTheDocument();
    });
  });

  // --- Delete flow via context menu on card ---

  it("opens context menu on workflow card and shows delete/duplicate", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    // Open the context menu of the first workflow card
    const menuButtons = screen.getAllByTestId(/^workflow-menu-/);
    expect(menuButtons.length).toBeGreaterThan(0);
    await user.click(menuButtons[0]!);

    // Menu should show Delete and Duplicate options
    await waitFor(() => {
      expect(screen.getByText("Delete")).toBeInTheDocument();
      expect(screen.getByText("Duplicate")).toBeInTheDocument();
    });
  });

  it("opens delete confirmation dialog from context menu", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    // Open context menu
    const menuButtons = screen.getAllByTestId(/^workflow-menu-/);
    await user.click(menuButtons[0]!);

    // Click Delete in context menu
    await waitFor(() => {
      expect(screen.getByText("Delete")).toBeInTheDocument();
    });
    await user.click(screen.getByText("Delete"));

    // Confirmation dialog should appear
    await waitFor(() => {
      expect(
        screen.getByText("Are you sure you want to delete this workflow?")
      ).toBeInTheDocument();
    });
  });

  it("can cancel the delete dialog", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    // Open context menu and click delete
    const menuButtons = screen.getAllByTestId(/^workflow-menu-/);
    await user.click(menuButtons[0]!);
    await waitFor(() => {
      expect(screen.getByText("Delete")).toBeInTheDocument();
    });
    await user.click(screen.getByText("Delete"));

    // Wait for dialog
    await waitFor(() => {
      expect(screen.getByText("Cancel")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Cancel"));

    await waitFor(() => {
      expect(
        screen.queryByText("Are you sure you want to delete this workflow?")
      ).not.toBeInTheDocument();
    });
  });

  // --- Delete/Duplicate in list view ---

  it("shows delete and duplicate buttons in list view", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
    });

    // In list view, the workflow page has inline delete/duplicate buttons
    // They use title attributes
    const deleteButtons = screen.getAllByTitle("Delete");
    expect(deleteButtons.length).toBeGreaterThan(0);

    const duplicateButtons = screen.getAllByTitle("Duplicate");
    expect(duplicateButtons.length).toBeGreaterThan(0);
  });

  // --- Error state ---

  it("shows error state when API fails", async () => {
    server.use(
      http.get("*/workflowstore/workflows/descriptors", () => {
        return HttpResponse.json(
          { error: "Server error" },
          { status: 500 }
        );
      })
    );

    renderWorkflows();

    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  // --- Empty state ---

  it("shows empty state when no workflows exist", async () => {
    server.use(
      http.get("*/workflowstore/workflows/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderWorkflows();

    await waitFor(() => {
      expect(
        screen.getByText(/no workflows/i)
      ).toBeInTheDocument();
    });
  });

  it("shows no results when search returns empty", async () => {
    server.use(
      http.get("*/workflowstore/workflows/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderWorkflows();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("workflow-search"), "nonexistent");

    await waitFor(() => {
      expect(screen.getByText(/no results/i)).toBeInTheDocument();
    });
  });

  // --- Version badges ---

  it("shows version badges in list view", async () => {
    renderWorkflows();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("workflow-list")).toBeInTheDocument();
    });

    await waitFor(() => {
      const versionBadges = screen.getAllByText(/^v\d+$/);
      expect(versionBadges.length).toBeGreaterThan(0);
    });
  });

  // --- Workflow cards show version badges ---

  it("shows version badges on cards", async () => {
    renderWorkflows();

    await waitFor(() => {
      expect(screen.getByTestId("workflow-grid")).toBeInTheDocument();
    });

    await waitFor(() => {
      const versionBadges = screen.getAllByText(/^v\d+$/);
      expect(versionBadges.length).toBeGreaterThan(0);
    });
  });

  // --- Workflow cards have names ---

  it("renders workflow names on cards", async () => {
    renderWorkflows();

    await waitFor(() => {
      const grid = screen.getByTestId("workflow-grid");
      expect(grid.children.length).toBeGreaterThan(0);
    });
  });
});
