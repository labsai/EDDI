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

  /**
   * The search box actually narrowing the list.
   *
   * The test above asserts the DOM input holds what was typed — a property of
   * `<input>`, not of this page. Nothing asserted the term reached the query,
   * so replacing `useInfiniteAgentDescriptors(search)` with
   * `useInfiniteAgentDescriptors("")` left all 28 tests here green. Verified
   * against a live EDDI 6.3.0 first: `filter` is a real query parameter and
   * genuinely narrows, so the handler now honours it too.
   */
  it("filters the list down to the matching agent", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Support Agent")).toBeInTheDocument();
    });
    expect(screen.getByText("FAQ Agent")).toBeInTheDocument();

    await user.type(screen.getByTestId("agent-search"), "FAQ");

    // Both halves: the match stays, the non-match goes. Asserting only the
    // first passes against a search box wired to nothing.
    await waitFor(() => {
      expect(screen.queryByText("Support Agent")).not.toBeInTheDocument();
    });
    expect(screen.getByText("FAQ Agent")).toBeInTheDocument();
  });

  it("shows the no-results empty state rather than the first-run one", async () => {
    renderAgents();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("agent-grid")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("agent-search"), "zzzznomatch");

    // A search with no hits is not a first run: offering "create your first
    // agent" to someone whose query simply missed is the wrong instruction,
    // and the action button must not be there to be clicked.
    await waitFor(() => {
      expect(screen.getByTestId("empty-state")).toBeInTheDocument();
    });
    expect(screen.getByTestId("empty-state")).toHaveTextContent(/no results/i);
    expect(
      within(screen.getByTestId("empty-state")).queryByRole("button"),
    ).not.toBeInTheDocument();
  });

  // --- Sorting in list view ---

  /**
   * The nth cell of every body row of the list view.
   *
   * Columns: 0 name, 1 id, 2 version, 3 modified, 4 actions.
   */
  const column = (index: number) =>
    within(screen.getByTestId("agent-list"))
      .getAllByRole("row")
      .slice(1) // row 0 is the header
      .map((r) => {
        // Not `?? ""`. A wrong index would otherwise yield a column of empty
        // strings, which sorts and reverses exactly like a real one — the
        // assertions below would pass while reading nothing.
        const cell = r.querySelectorAll("td")[index];
        if (!cell) throw new Error(`row has no cell at index ${index}`);
        return cell.textContent?.trim() ?? "";
      });

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

    // Compare the WHOLE rendered column against a sorted copy of itself.
    // Comparing only the first and last row (what this used to do) passes on a
    // list whose middle is shuffled, and the descending half asserted
    // `localeCompare(...) >= 0`, which also passes when the two are equal —
    // i.e. when the sort did nothing at all.
    const namesInOrder = () => column(0);

    const ascending = namesInOrder();
    expect(ascending.length).toBeGreaterThan(1);
    expect(ascending).toEqual([...ascending].sort((a, b) => a.localeCompare(b)));

    // Click again to reverse (desc)
    await user.click(nameButton);

    const descending = namesInOrder();
    expect(descending).toEqual([...ascending].reverse());
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

    // The whole column against a sorted copy of itself, the same way the name
    // case above does it. "the list is still rendered" — what this asserted —
    // was already true before the click, so it passed against a comparator
    // that did nothing: replacing the non-name branches of `agents.tsx`'s
    // comparator with `cmp = 0` left this green.
    const versionsInOrder = () =>
      column(2).map((cell) => Number(cell.replace(/^v/, "")));

    const versionButton = screen.getByLabelText("Sort by version");
    await user.click(versionButton);

    const ascending = versionsInOrder();
    expect(ascending.length).toBeGreaterThan(1);
    expect(ascending).toEqual([...ascending].sort((a, b) => a - b));

    await user.click(versionButton);
    expect(versionsInOrder()).toEqual([...ascending].reverse());
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

    // Nothing here parses the date cell. It renders `toLocaleString()`, so it
    // reads "22.8.2026, 01:05:32" here and "8/22/2026, 1:05:32 AM" on a CI
    // runner, and `Date.parse` returns NaN for one and a confidently wrong
    // value for the other. The assertions below compare the column against
    // itself and against row identity, both of which are locale-independent.
    const modifiedButton = screen.getByLabelText("Sort by last modified");
    await user.click(modifiedButton);

    // The DATE column, not the name column: two fixture agents share a
    // timestamp, and a stable sort keeps tied rows in insertion order both ways,
    // so the row identities are not an exact reverse of each other. The rendered
    // values are — equal strings are interchangeable.
    const ascending = column(3);
    const namesNow = column(0);
    expect(ascending.length).toBeGreaterThan(1);

    // Which END is which, not just that the two clicks mirror each other.
    // `toEqual(reverse)` alone holds under ANY total order, so flipping the
    // comparator to `b - a` — turning the default newest-first list into
    // oldest-first, which a user would notice — sailed through it. The fixture's
    // oldest agent is Appointment Scheduler (2 days) and its newest is IT
    // Helpdesk Bot (30 minutes).
    expect(namesNow[0]).toBe("Appointment Scheduler");
    expect(namesNow[namesNow.length - 1]).toBe("IT Helpdesk Bot");

    // A comparator that does nothing leaves the order untouched, so the second
    // click would return the same column rather than its reverse.
    await user.click(modifiedButton);
    expect(column(3)).toEqual([...ascending].reverse());

    // And it is reading `lastModifiedOn` rather than falling back to the name.
    // The fixture's two orders genuinely differ — modified puts Invoice Analyst
    // second where alphabetical puts Contract Review Assistant — so ruling out
    // BOTH alphabetical directions rules out a name comparator.
    //
    // Both directions, because one is not enough: swapping this branch to
    // `localeCompare` and checking only the ascending form still passed, since a
    // name comparator can land either way round depending on sort direction.
    // Checked, then fixed.
    const alphabetical = [...namesNow].sort((a, b) => a.localeCompare(b));
    expect(namesNow).not.toEqual(alphabetical);
    expect(namesNow).not.toEqual([...alphabetical].reverse());
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
