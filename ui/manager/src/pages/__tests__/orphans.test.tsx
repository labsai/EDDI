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
      expect(screen.getByText("5 orphan(s) found")).toBeInTheDocument();
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

    // Confirmation dialog should appear
    expect(screen.getByText("Are you sure?")).toBeInTheDocument();
    expect(screen.getByTestId("confirm-purge-button")).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
  });

  it("cancel button in purge confirmation hides it", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("scan-button"));

    await waitFor(() => {
      expect(screen.getByTestId("purge-button")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("purge-button"));
    expect(screen.getByText("Are you sure?")).toBeInTheDocument();

    await user.click(screen.getByText("Cancel"));
    expect(screen.queryByText("Are you sure?")).not.toBeInTheDocument();
  });

  it("pre-scan button triggers scan too", async () => {
    renderOrphans();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("pre-scan-button"));

    await waitFor(() => {
      expect(screen.getByText("5 orphan(s) found")).toBeInTheDocument();
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
