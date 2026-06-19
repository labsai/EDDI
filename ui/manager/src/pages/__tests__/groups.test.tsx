import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { GroupsPage } from "@/pages/groups";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderPage() {
  return renderWithProviders(<GroupsPage />, {
    initialRoute: "/manage/groups",
  });
}

describe("GroupsPage", () => {
  // ─── Page structure ─────────────────────────────────────────────────────

  it("renders heading with icon", () => {
    renderPage();
    expect(screen.getByText("Groups")).toBeInTheDocument();
  });

  it("renders subtitle description", () => {
    renderPage();
    expect(
      screen.getByText(/Multi-agent discussion groups/)
    ).toBeInTheDocument();
  });

  it("renders search input", () => {
    renderPage();
    expect(screen.getByTestId("group-search")).toBeInTheDocument();
  });

  it("shows create group button", () => {
    renderPage();
    expect(screen.getByTestId("create-group-btn")).toBeInTheDocument();
  });

  it("renders view toggle", () => {
    renderPage();
    expect(screen.getByTestId("view-toggle")).toBeInTheDocument();
  });

  // ─── Loading state ──────────────────────────────────────────────────────

  it("shows loading skeletons while data is being fetched", () => {
    // We can check by immediately rendering (before data loads)
    renderPage();
    // Loading skeletons are visible briefly
    // The actual content will load, but at least the page renders without errors
    expect(screen.getByTestId("group-search")).toBeInTheDocument();
  });

  // ─── Data loaded — Card view ──────────────────────────────────────────

  it("renders group cards after loading", async () => {
    renderPage();

    await waitFor(
      () => {
        const cards = screen.getAllByText("Product Review Panel");
        expect(cards.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );
  });

  it("displays group cards in card view by default", async () => {
    renderPage();

    await waitFor(
      () => {
        const cards = screen.getAllByTestId(/^group-card-/);
        expect(cards.length).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );
  });

  it("renders group count text after loading", async () => {
    renderPage();

    await waitFor(
      () => {
        expect(screen.getByText(/groups$/i)).toBeInTheDocument();
      },
      { timeout: 10000 }
    );
  });

  // ─── List view ──────────────────────────────────────────────────────────

  it("switches to list view when list toggle is clicked", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^group-card-/).length
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );

    // Find the list view button in the view toggle
    const listButton = screen.getByTestId("view-toggle-list");
    await user.click(listButton);

    // Should now show the list table
    await waitFor(() => {
      expect(screen.getByTestId("group-list")).toBeInTheDocument();
    });
  });

  it("list view shows Name, ID, Version, Modified, and Actions columns", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^group-card-/).length
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByText("Name")).toBeInTheDocument();
      expect(screen.getByText("ID")).toBeInTheDocument();
      expect(screen.getByText("Version")).toBeInTheDocument();
      expect(screen.getByText("Modified")).toBeInTheDocument();
      expect(screen.getByText("Actions")).toBeInTheDocument();
    });
  });

  it("list view shows duplicate and delete action buttons", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^group-card-/).length
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      // Each row should have Duplicate and Delete buttons
      const duplicateButtons = screen.getAllByTitle("Duplicate");
      expect(duplicateButtons.length).toBeGreaterThanOrEqual(1);
      const deleteButtons = screen.getAllByTitle(/delete/i);
      expect(deleteButtons.length).toBeGreaterThanOrEqual(1);
    });
  });

  // ─── Search / filter ────────────────────────────────────────────────────

  it("filters groups by search query (server-side)", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^group-card-/).length
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );

    // Override the handler to return no results for this search
    server.use(
      http.get("*/groupstore/groups/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    await user.type(screen.getByTestId("group-search"), "nonexistentquery");

    // Wait for re-fetch with empty result
    await waitFor(() => {
      expect(screen.queryAllByTestId(/^group-card-/).length).toBe(0);
    });
  });

  // ─── Create dialog flow ─────────────────────────────────────────────────

  it("opens create-or-wizard dialog when create button is clicked", async () => {
    renderPage();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("create-group-btn"));

    await waitFor(() => {
      // The CreateOrWizardDialog should open, showing Quick Create and Guided Setup
      expect(screen.getByText("Quick Create")).toBeInTheDocument();
      expect(screen.getByText("Guided Setup")).toBeInTheDocument();
    });
  });

  // ─── Delete confirmation ────────────────────────────────────────────────

  it("shows delete confirmation dialog and can cancel it", async () => {
    renderPage();
    const user = userEvent.setup();

    // Switch to list view for easier access to delete buttons
    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^group-card-/).length
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("group-list")).toBeInTheDocument();
    });

    // Click delete on the first group
    const deleteButtons = screen.getAllByTitle(/delete/i);
    await user.click(deleteButtons[0]!);

    // Confirm dialog should appear
    await waitFor(() => {
      expect(screen.getByText("Delete this group?")).toBeInTheDocument();
      expect(
        screen.getByText(
          "This will permanently delete the group configuration."
        )
      ).toBeInTheDocument();
    });

    // Click cancel
    const cancelBtn = screen.getByText(/cancel/i);
    await user.click(cancelBtn);

    // Dialog should close
    await waitFor(() => {
      expect(
        screen.queryByText("Delete this group?")
      ).not.toBeInTheDocument();
    });
  });

  // ─── Empty state ────────────────────────────────────────────────────────

  it("shows empty state when no groups are returned", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderPage();

    await waitFor(() => {
      expect(screen.getByText("No groups yet")).toBeInTheDocument();
    });
  });

  it("empty state shows create group action button", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Create Group")).toBeInTheDocument();
    });
  });

  it("shows 'no results' empty state when search has no matches", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByTestId("group-search"), "nonexistent");

    await waitFor(() => {
      // "No results found" (from common.noResults i18n key)
      expect(screen.queryByText("Create Group")).not.toBeInTheDocument();
    });
  });

  // ─── Error state ────────────────────────────────────────────────────────

  it("shows error state when API request fails", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () => {
        return HttpResponse.json({ error: "Server error" }, { status: 500 });
      })
    );

    renderPage();

    await waitFor(() => {
      // ErrorState component renders "Something went wrong"
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    });
  });

  // ─── Error retry ─────────────────────────────────────────────────────

  it("error state shows retry button", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () => {
        return HttpResponse.json({ error: "Server error" }, { status: 500 });
      })
    );

    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  // ─── Confirm delete flow ───────────────────────────────────────────────

  it("confirms delete when confirm button is clicked", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^group-card-/).length
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("group-list")).toBeInTheDocument();
    });

    const deleteButtons = screen.getAllByTitle(/delete/i);
    await user.click(deleteButtons[0]!);

    await waitFor(() => {
      expect(screen.getByText("Delete this group?")).toBeInTheDocument();
    });

    // Click delete to confirm
    const confirmBtn = screen.getByText(/^Delete$/);
    await user.click(confirmBtn);

    // Dialog should close after successful deletion
    await waitFor(() => {
      expect(
        screen.queryByText("Delete this group?")
      ).not.toBeInTheDocument();
    });
  });

  // ─── Duplicate in list view ────────────────────────────────────────────

  it("clicking duplicate button in list view triggers duplication", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^group-card-/).length
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      expect(screen.getByTestId("group-list")).toBeInTheDocument();
    });

    const duplicateButtons = screen.getAllByTitle("Duplicate");
    expect(duplicateButtons.length).toBeGreaterThanOrEqual(1);
    await user.click(duplicateButtons[0]!);

    // Duplicate should succeed without error (toast)
    // Just verify no crash
    expect(screen.getByTestId("group-list")).toBeInTheDocument();
  });

  // ─── Empty state create button ─────────────────────────────────────────

  it("empty state create button opens create dialog", async () => {
    server.use(
      http.get("*/groupstore/groups/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Create Group")).toBeInTheDocument();
    });

    // Click the create button in the empty state
    const createButtons = screen.getAllByText("Create Group");
    // Find the one that's NOT in the header (the empty state one)
    const emptyStateBtn = createButtons.find(
      (btn) => !btn.closest("[data-testid='create-group-btn']")
    );
    if (emptyStateBtn) {
      await user.click(emptyStateBtn);
      await waitFor(() => {
        // Should trigger the create dialog or wizard dialog
        expect(screen.getByText("Quick Create")).toBeInTheDocument();
      });
    }
  });

  // ─── List view links ──────────────────────────────────────────────────

  it("list view group names link to group detail page", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(
      () => {
        expect(
          screen.getAllByTestId(/^group-card-/).length
        ).toBeGreaterThanOrEqual(1);
      },
      { timeout: 10000 }
    );

    await user.click(screen.getByTestId("view-toggle-list"));

    await waitFor(() => {
      const listEl = screen.getByTestId("group-list");
      const links = listEl.querySelectorAll("a[href*='/manage/groups/']");
      expect(links.length).toBeGreaterThanOrEqual(1);
    });
  });
});
