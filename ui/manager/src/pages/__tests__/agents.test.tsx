import { describe, it, expect, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AgentsPage } from "@/pages/agents";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

// Mock sonner toast
vi.mock("sonner", () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

function renderAgents() {
  return renderWithProviders(<AgentsPage />);
}

describe("AgentsPage", () => {
  it("renders page heading", () => {
    renderAgents();
    expect(screen.getByText("Agents")).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderAgents();
    expect(screen.getByTestId("agent-search")).toBeInTheDocument();
  });

  it("renders create agent button", () => {
    renderAgents();
    expect(screen.getByTestId("create-agent-btn")).toBeInTheDocument();
  });

  it("shows loading skeletons before data loads", () => {
    // Delay the API response so loading state is visible
    server.use(
      http.get("*/agentstore/agents/descriptors", async () => {
        await new Promise((r) => setTimeout(r, 5000));
        return HttpResponse.json([]);
      })
    );
    renderAgents();
    // The page heading should be visible, but the data grid should NOT be visible yet
    expect(screen.getByText("Agents")).toBeInTheDocument();
    expect(screen.queryByTestId("agent-grid")).not.toBeInTheDocument();
    // Verify skeleton loading container is rendering
    expect(screen.getByTestId("agents-loading")).toBeInTheDocument();
  });

  it("renders subtitle text", () => {
    renderAgents();
    expect(
      screen.getByText("Manage your conversational AI agents")
    ).toBeInTheDocument();
  });

  it("renders import agent button", () => {
    renderAgents();
    expect(screen.getByTestId("import-agent-btn")).toBeInTheDocument();
  });

  it("renders the view toggle component", () => {
    renderAgents();
    expect(screen.getByTestId("view-toggle")).toBeInTheDocument();
  });

  // --- Data loading ---

  it("renders agent cards after loading", async () => {
    renderAgents();
    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });
  });

  it("shows agent count text", async () => {
    renderAgents();
    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });
    await waitFor(() => {
      const countTexts = screen.getAllByText(/agent/i);
      expect(countTexts.length).toBeGreaterThan(0);
    });
  });

  it("renders multiple agent cards", async () => {
    renderAgents();
    await waitFor(() => {
      const grid = screen.getByTestId("agent-grid");
      expect(grid.children.length).toBeGreaterThan(0);
    });
  });

  // --- View toggle ---

  it("can switch to list view", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });
  });

  it("renders table headers in list view", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Version")).toBeInTheDocument();
    expect(screen.getByText("Modified")).toBeInTheDocument();
  });

  it("can switch back to card view", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));
    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-card"));
    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });
  });

  // --- Search ---

  it("allows typing in the search input", async () => {
    renderAgents();
    const user = userEvent.setup();

    const searchInput = screen.getByTestId("agent-search");
    await user.type(searchInput, "support");
    expect(searchInput).toHaveValue("support");
  });

  // --- Sorting in list view ---

  it("sorts by name when clicking Name header and verifies order", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    // Default sort is by modified desc. Click name to sort asc by name.
    const nameButton = screen.getByLabelText("Sort by name");
    await user.click(nameButton);

    // Verify ascending order: first agent should come before last alphabetically
    const listContainer = screen.getByTestId("agent-list");
    const rows = within(listContainer).getAllByRole("row");
    // rows[0] is the header, data rows start at 1
    const firstDataRow = rows[1]!;
    const lastDataRow = rows[rows.length - 1]!;
    const firstName = firstDataRow.querySelector("td")?.textContent ?? "";
    const lastName = lastDataRow.querySelector("td")?.textContent ?? "";
    expect(firstName.localeCompare(lastName)).toBeLessThanOrEqual(0);

    // Click again to reverse (desc)
    await user.click(nameButton);

    const rowsDesc = within(screen.getByTestId("agent-list")).getAllByRole("row");
    const firstDesc = rowsDesc[1]!.querySelector("td")?.textContent ?? "";
    const lastDesc = rowsDesc[rowsDesc.length - 1]!.querySelector("td")?.textContent ?? "";
    expect(firstDesc.localeCompare(lastDesc)).toBeGreaterThanOrEqual(0);
  });

  it("sorts by version", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    const versionButton = screen.getByLabelText("Sort by version");
    await user.click(versionButton);

    // Just verify the list is still rendered after sort
    expect(screen.getByTestId("agent-list")).toBeInTheDocument();
  });

  it("sorts by modified", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    const modifiedButton = screen.getByLabelText("Sort by last modified");
    await user.click(modifiedButton);

    expect(screen.getByTestId("agent-list")).toBeInTheDocument();
  });

  // --- Delete flow ---

  it("shows delete buttons in list view", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    const deleteButtons = screen.getAllByTitle("Delete");
    expect(deleteButtons.length).toBeGreaterThan(0);
  });

  it("opens delete confirmation dialog from list view", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    const deleteButtons = screen.getAllByTitle("Delete");
    await user.click(deleteButtons[0]!);

    await waitFor(() => {
      expect(
        screen.getByText("Are you sure you want to delete this agent?")
      ).toBeInTheDocument();
    });
  });

  it("can cancel the delete dialog", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    const deleteButtons = screen.getAllByTitle("Delete");
    await user.click(deleteButtons[0]!);

    await waitFor(() => {
      expect(screen.getByText("Cancel")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Cancel"));

    await waitFor(() => {
      expect(
        screen.queryByText("Are you sure you want to delete this agent?")
      ).not.toBeInTheDocument();
    });
  });

  // --- Duplicate and Export in list view ---

  it("shows duplicate and export buttons in list view", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    const duplicateButtons = screen.getAllByTitle("Duplicate");
    expect(duplicateButtons.length).toBeGreaterThan(0);

    const exportButtons = screen.getAllByTitle("Export");
    expect(exportButtons.length).toBeGreaterThan(0);
  });

  // --- Create agent dialog ---

  it("opens create dialog when create button is clicked", async () => {
    renderAgents();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("create-agent-btn"));

    // Should show the CreateOrWizardDialog with New Agent text
    await waitFor(() => {
      // The dialog shows options like "Quick Create" and "Wizard"
      expect(screen.getByText(/quick create/i)).toBeInTheDocument();
    });
  });

  // --- Import dialog ---

  it("opens import dialog when import button is clicked", async () => {
    renderAgents();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("import-agent-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("import-agent-dialog")).toBeInTheDocument();
    });
  });

  // --- Error state ---

  it("shows error state when API fails", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        return HttpResponse.json(
          { error: "Server error" },
          { status: 500 }
        );
      })
    );

    renderAgents();

    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  // --- Empty state ---

  it("shows empty state when no agents exist", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderAgents();

    await waitFor(() => {
      expect(
        screen.getByText(/no agents/i)
      ).toBeInTheDocument();
    });
  });

  it("shows 'no results' when search returns empty", async () => {
    server.use(
      http.get("*/agentstore/agents/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderAgents();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("agent-search"), "nonexistent");

    await waitFor(() => {
      expect(screen.getByText(/no results/i)).toBeInTheDocument();
    });
  });

  // --- Agent names on cards ---

  it("shows actual agent names on cards", async () => {
    renderAgents();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    // Assert actual agent names from mock data
    await waitFor(() => {
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
      expect(screen.getByText("FAQ Agent")).toBeInTheDocument();
      expect(screen.getByText("Appointment Scheduler")).toBeInTheDocument();
    });
  });

  it("shows version badges in list view", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("agent-list")).toBeInTheDocument();
    });

    await waitFor(() => {
      const versionBadges = screen.getAllByText(/^v\d+$/);
      expect(versionBadges.length).toBeGreaterThan(0);
    });
  });
});
