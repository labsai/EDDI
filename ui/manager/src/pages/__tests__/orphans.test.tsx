import { describe, it, expect, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { OrphansPage } from "@/pages/orphans";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

// Mock the onboarding store so maybeAutoStart is a no-op
vi.mock("@/hooks/use-onboarding", () => ({
  useOnboarding: () => vi.fn(),
}));

function renderOrphans() {
  return renderWithProviders(<OrphansPage />);
}

describe("OrphansPage", () => {
  it("renders the page container", () => {
    renderOrphans();
    expect(screen.getByTestId("orphans-page")).toBeInTheDocument();
  });

  it("renders the title and description", () => {
    renderOrphans();
    expect(screen.getByText("Orphan Detection")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Find and clean up resources not referenced by any agent or package."
      )
    ).toBeInTheDocument();
  });

  it("shows pre-scan empty state with scan buttons", () => {
    renderOrphans();
    expect(screen.getByTestId("pre-scan-state")).toBeInTheDocument();
    expect(screen.getByTestId("pre-scan-button")).toBeInTheDocument();
    expect(screen.getByTestId("scan-button")).toBeInTheDocument();
  });

  it("shows include-deleted checkbox", () => {
    renderOrphans();
    expect(screen.getByTestId("include-deleted-checkbox")).toBeInTheDocument();
  });

  it("toggles include-deleted checkbox", async () => {
    renderOrphans();
    const user = userEvent.setup();
    const checkbox = screen.getByTestId(
      "include-deleted-checkbox"
    ) as HTMLInputElement;
    expect(checkbox.checked).toBe(false);
    await user.click(checkbox);
    expect(checkbox.checked).toBe(true);
  });

  it("triggers scan and shows results grouped by type", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getByText("5 orphans found")).toBeInTheDocument();
    });

    // Should show orphan names from mock data
    expect(
      screen.getByText("Legacy Support Workflow (v1)")
    ).toBeInTheDocument();
    expect(
      screen.getByText("Deprecated Greeting Rules")
    ).toBeInTheDocument();
    expect(
      screen.getByText("Archived French Responses")
    ).toBeInTheDocument();
    expect(
      screen.getByText("Old GPT-3.5 Config")
    ).toBeInTheDocument();
  });

  it("shows version badges for orphans with versions", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      // Orphan3 has version=2
      expect(screen.getByText("v2")).toBeInTheDocument();
    });
  });

  it("shows deleted badges on soft-deleted orphans", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      // Two orphans are marked deleted in mock
      const deletedBadges = screen.getAllByText("Deleted");
      expect(deletedBadges.length).toBe(2);
    });
  });

  it("shows select all button after scan", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getByTestId("select-all-btn")).toBeInTheDocument();
    });
  });

  it("toggles individual orphan selection", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getAllByTestId("orphan-checkbox-0").length).toBeGreaterThan(0);
    });

    const cb = screen.getAllByTestId("orphan-checkbox-0")[0] as HTMLInputElement;
    expect(cb.checked).toBe(false);

    await user.click(cb);
    expect(cb.checked).toBe(true);

    // Should now show delete selected button
    expect(screen.getByTestId("delete-selected-btn")).toBeInTheDocument();

    // Deselect
    await user.click(cb);
    expect(cb.checked).toBe(false);
  });

  it("select all / deselect all", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getByTestId("select-all-btn")).toBeInTheDocument();
    });

    // Select all
    await user.click(screen.getByTestId("select-all-btn"));

    // All orphan checkboxes should be checked (5 orphans, each is index 0 in its group)
    const allCheckboxes = screen.getAllByTestId("orphan-checkbox-0");
    expect(allCheckboxes.length).toBe(5);
    for (const cb of allCheckboxes) {
      expect((cb as HTMLInputElement).checked).toBe(true);
    }

    // Button should now say "Deselect All"
    expect(screen.getByText("Deselect All")).toBeInTheDocument();

    // Deselect all
    await user.click(screen.getByTestId("select-all-btn"));
    for (const cb of allCheckboxes) {
      expect((cb as HTMLInputElement).checked).toBe(false);
    }
  });

  it("shows purge button and confirmation dialog", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getByTestId("purge-button")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("purge-button"));

    // Confirmation should state the true (all-orphans) scope, not just "Are you sure?"
    expect(
      screen.getByText(/Permanently delete ALL 5 orphaned resources/),
    ).toBeInTheDocument();
    expect(screen.getByTestId("confirm-purge-button")).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
  });

  // Regression: the backend DELETE /administration/orphans has no selection param
  // — it always purges every orphan. The "Delete N selected" path must say so.
  it("warns that ALL orphans are purged even from the 'Delete selected' action", async () => {
    renderOrphans();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("scan-button"));
    await waitFor(() => {
      expect(screen.getAllByTestId("orphan-checkbox-0").length).toBeGreaterThan(0);
    });
    // Select a single orphan, then click "Delete N selected"
    await user.click(screen.getAllByTestId("orphan-checkbox-0")[0]!);
    await user.click(screen.getByTestId("delete-selected-btn"));
    // The confirmation makes clear selection is NOT honored and ALL are deleted
    expect(
      screen.getByText(/your selection is not applied/i),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/Permanently delete ALL 5 orphaned resources/),
    ).toBeInTheDocument();
  });

  it("cancel button in purge confirmation hides it", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getByTestId("purge-button")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("purge-button"));
    expect(
      screen.getByText(/Permanently delete ALL 5 orphaned resources/),
    ).toBeInTheDocument();

    await user.click(screen.getByText("Cancel"));
    expect(
      screen.queryByText(/Permanently delete ALL 5 orphaned resources/),
    ).not.toBeInTheDocument();
  });

  it("pre-scan button triggers scan too", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("pre-scan-button"));

    await waitFor(() => {
      expect(screen.getByText("5 orphans found")).toBeInTheDocument();
    });
  });

  it("shows group checkboxes for type groups", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      // Groups are keyed by the type URIs
      expect(
        screen.getByTestId("group-checkbox-eddi://ai.labs.workflow")
      ).toBeInTheDocument();
      expect(
        screen.getByTestId("group-checkbox-eddi://ai.labs.rules")
      ).toBeInTheDocument();
    });
  });

  it("toggles group checkbox to select/deselect all in group", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(
        screen.getByTestId("group-checkbox-eddi://ai.labs.workflow")
      ).toBeInTheDocument();
    });

    // The workflow group only has 1 orphan (orphan1)
    const groupCb = screen.getByTestId(
      "group-checkbox-eddi://ai.labs.workflow"
    ) as HTMLInputElement;
    await user.click(groupCb);
    expect(groupCb.checked).toBe(true);

    // Deselect via group checkbox
    await user.click(groupCb);
    expect(groupCb.checked).toBe(false);
  });

  it("shows empty results after scan when no orphans", async () => {
    server.use(
      http.get("*/administration/orphans", () => {
        return HttpResponse.json({
          totalOrphans: 0,
          deletedCount: 0,
          orphans: [],
        });
      })
    );

    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getByText("No orphans found")).toBeInTheDocument();
    });

    // No select all or purge button when 0 orphans
    expect(screen.queryByTestId("select-all-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("purge-button")).not.toBeInTheDocument();
  });

  it("shows delete selected button when items selected and hides purge all", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getAllByTestId("orphan-checkbox-0").length).toBeGreaterThan(0);
    });

    // Before selection - purge button visible, delete-selected not
    expect(screen.getByTestId("purge-button")).toBeInTheDocument();
    expect(screen.queryByTestId("delete-selected-btn")).not.toBeInTheDocument();

    // Select one
    await user.click(screen.getAllByTestId("orphan-checkbox-0")[0]!);

    // Now delete-selected visible, purge-button hidden
    expect(screen.getByTestId("delete-selected-btn")).toBeInTheDocument();
    expect(screen.queryByTestId("purge-button")).not.toBeInTheDocument();
  });
});

/**
 * An incomplete reference scan makes MORE resources look unreferenced, never
 * fewer, so the list it produces is a set of false positives rather than a
 * shorter true one. EDDI refuses to purge on one — 409 `incomplete_scan` — so
 * the page's job is to say why before the operator reaches for the button.
 */
describe("OrphansPage — incomplete scan", () => {
  /** Serve one scan result and run a scan. */
  async function scanReturning(body: Record<string, unknown>) {
    server.use(
      http.get("*/administration/orphans", () => HttpResponse.json(body)),
    );
    renderOrphans();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("scan-button"));
  }

  const partial = {
    totalOrphans: 2,
    deletedCount: 0,
    scanComplete: false,
    scanWarning: "reverse lookup failed for eddi://ai.labs.workflow",
    orphans: [
      {
        resourceUri: "eddi://ai.labs.output/outputstore/outputsets/o1?version=1",
        type: "eddi://ai.labs.output",
        name: "Maybe orphaned",
        deleted: false,
      },
    ],
  };

  it("warns and names the cause when the scan did not finish", async () => {
    await scanReturning(partial);

    await waitFor(() => {
      expect(screen.getByTestId("orphans-scan-incomplete")).toBeInTheDocument();
    });
    expect(screen.getByTestId("orphans-scan-warning")).toHaveTextContent(
      "reverse lookup failed",
    );
  });

  it("withholds the purge control while the scan is incomplete", async () => {
    // The list still renders — it is worth reading, just not worth acting on.
    await scanReturning(partial);

    await waitFor(() => {
      expect(screen.getByTestId("orphans-scan-incomplete")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("purge-button")).not.toBeInTheDocument();
  });

  it("says nothing and offers the purge when the scan finished", async () => {
    // The other direction: a warning shown unconditionally would satisfy the
    // tests above while blocking every ordinary purge.
    await scanReturning({ ...partial, scanComplete: true, scanWarning: null });

    await waitFor(() => {
      expect(screen.getByTestId("purge-button")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("orphans-scan-incomplete")).not.toBeInTheDocument();
  });

  it("treats a report with no flag at all as complete", async () => {
    // An EDDI predating the field. Reading absence as incomplete would block
    // purging on every existing deployment.
    await scanReturning({
      totalOrphans: partial.totalOrphans,
      deletedCount: partial.deletedCount,
      orphans: partial.orphans,
    });

    await waitFor(() => {
      expect(screen.getByTestId("purge-button")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("orphans-scan-incomplete")).not.toBeInTheDocument();
  });
});
