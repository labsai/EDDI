import { describe, it, expect, afterEach, beforeAll } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { GdprPage } from "@/pages/gdpr";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderPage() {
  return renderWithProviders(<GdprPage />, {
    initialRoute: "/manage/gdpr",
  });
}

describe("GDPR Privacy Admin Page", () => {
  // jsdom implements neither half of the object-URL API, so the export's
  // download step throws there. Stubbed rather than skipped: without it the
  // handler dies mid-way and the completeness assertions below would be
  // measuring an exception, not the page.
  beforeAll(() => {
    Object.assign(globalThis.URL, {
      createObjectURL: () => "blob:stub",
      revokeObjectURL: () => {},
    });
  });

  afterEach(() => {
    server.resetHandlers();
  });

  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText(/Privacy & Compliance/i)).toBeInTheDocument();
  });

  it("renders the user ID input", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-user-id")).toBeInTheDocument();
  });

  it("renders export and delete buttons", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-export-btn")).toBeInTheDocument();
    expect(screen.getByTestId("gdpr-delete-btn")).toBeInTheDocument();
  });

  it("disables buttons when user ID is empty", () => {
    renderPage();
    const exportBtn = screen.getByTestId("gdpr-export-btn");
    const deleteBtn = screen.getByTestId("gdpr-delete-btn");
    expect(exportBtn).toBeDisabled();
    expect(deleteBtn).toBeDisabled();
  });

  it("enables buttons when user ID is entered", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "user-123");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-export-btn")).not.toBeDisabled();
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });
  });

  it("shows confirmation dialog when delete is clicked", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "user-123");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });

    await user.click(screen.getByTestId("gdpr-delete-btn"));

    await waitFor(() => {
      expect(screen.getByText(/Confirm Data Deletion/i)).toBeInTheDocument();
    });
  });

  it("shows deletion results after confirming delete and verifies API call", async () => {
    let deleteCalled = false;
    server.use(
      http.delete("*/admin/gdpr/:userId", () => {
        deleteCalled = true;
        return HttpResponse.json({
          memoriesDeleted: 14,
          conversationsDeleted: 7,
          auditPseudonymized: 42,
          logsPseudonymized: 21,
        });
      })
    );

    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "user-123");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });

    await user.click(screen.getByTestId("gdpr-delete-btn"));

    await waitFor(() => {
      expect(screen.getByText(/Confirm Data Deletion/i)).toBeInTheDocument();
    });

    const confirmBtn = screen.getByText(/Yes, Delete All Data/i);
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-results")).toBeInTheDocument();
    });

    expect(deleteCalled).toBe(true);
    // Check the result cards show the mock values
    expect(screen.getByText("14")).toBeInTheDocument(); // memoriesDeleted
    expect(screen.getByText("7")).toBeInTheDocument(); // conversationsDeleted
  });

  it("renders the legal notice banner", () => {
    renderPage();
    expect(screen.getByText(/Data Protection Notice/i)).toBeInTheDocument();
  });

  // ─── Subtitle & descriptions ────────────────────────────────────────────

  it("renders the page subtitle", () => {
    renderPage();
    expect(screen.getByText(/GDPR-compliant user data management/)).toBeInTheDocument();
  });

  it("renders the legal description about Art. 15/20", () => {
    renderPage();
    expect(screen.getByText(/Data export.*Art\. 15\/20/)).toBeInTheDocument();
  });

  it("renders the user lookup section header", () => {
    renderPage();
    expect(screen.getByText("User Lookup")).toBeInTheDocument();
  });

  // ─── Processing Restriction section ─────────────────────────────────────

  it("renders the restriction section", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-restriction-section")).toBeInTheDocument();
  });

  it("shows restriction section title", () => {
    renderPage();
    expect(screen.getByText(/Processing Restriction.*Art\. 18/)).toBeInTheDocument();
  });

  it("shows restriction description", () => {
    renderPage();
    expect(screen.getByText(/Restrict processing when a user disputes/)).toBeInTheDocument();
  });

  it("shows 'enter user ID first' hint when no user ID", () => {
    renderPage();
    expect(screen.getByText(/Enter a user ID above to check status/)).toBeInTheDocument();
  });

  it("shows restrict toggle button", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-restrict-toggle")).toBeInTheDocument();
  });

  it("restrict toggle button is disabled when no user ID", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-restrict-toggle")).toBeDisabled();
  });

  it("restrict toggle button is enabled after entering user ID", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "test-user");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-restrict-toggle")).not.toBeDisabled();
    });
  });

  it("shows 'Processing Active' badge after entering user ID (non-restricted)", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "active-user");

    await waitFor(() => {
      expect(screen.getByTestId("restriction-badge-active")).toBeInTheDocument();
      expect(screen.getByText("Processing Active")).toBeInTheDocument();
    });
  });

  it("shows 'Restrict Processing' label on toggle for non-restricted user", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "active-user");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-restrict-toggle")).toHaveTextContent("Restrict Processing");
    });
  });

  it("clears result when user ID changes", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "user-123");

    // Click delete
    await waitFor(() => {
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });
    await user.click(screen.getByTestId("gdpr-delete-btn"));

    await waitFor(() => {
      expect(screen.getByText(/Confirm Data Deletion/i)).toBeInTheDocument();
    });

    const confirmBtn = screen.getByText(/Yes, Delete All Data/i);
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-results")).toBeInTheDocument();
    });

    // Change user ID - result should clear
    await user.clear(input);
    await user.type(input, "different-user");

    await waitFor(() => {
      expect(screen.queryByTestId("gdpr-results")).not.toBeInTheDocument();
    });
  });

  /** Erase `userId` and wait for the results panel. */
  async function runErasure(userId = "user-123") {
    const user = userEvent.setup();
    await user.type(screen.getByTestId("gdpr-user-id"), userId);
    await waitFor(() => {
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });
    await user.click(screen.getByTestId("gdpr-delete-btn"));
    await waitFor(() => {
      expect(screen.getByText(/Confirm Data Deletion/i)).toBeInTheDocument();
    });
    await user.click(screen.getByText(/Yes, Delete All Data/i));
    await waitFor(() => {
      expect(screen.getByTestId("gdpr-results")).toBeInTheDocument();
    });
  }

  it("result cards show every counter the cascade reports, with its value", async () => {
    // Asserting the VALUE, not just the label. This test used to check the four
    // labels only, which is how `logEntriesPseudonymized` — a field name the
    // backend has never sent — survived: the label rendered, the number beside
    // it was `undefined`, and nothing looked.
    renderPage();
    await runErasure();

    for (const [label, value] of [
      ["Memories Deleted", "14"],
      ["Conversations Deleted", "7"],
      ["Mappings Deleted", "2"],
      ["Audit Pseudonymized", "23"],
      ["Logs Pseudonymized", "89"],
      ["Attachments Deleted", "4"],
      ["Group Conversations Deleted", "3"],
      ["Schedules Deleted", "1"],
    ] as const) {
      const card = screen.getByText(label).closest("div");
      expect(card).toHaveTextContent(value);
    }
  });

  it("renders a dash, not a zero, for a counter the backend did not send", async () => {
    // Six counters are newer than the endpoint. Showing `0` for an absent field
    // would claim the cascade found nothing to delete when it never looked.
    server.use(
      http.delete("*/admin/gdpr/:userId", ({ params }) =>
        HttpResponse.json({
          userId: params.userId as string,
          memoriesDeleted: 14,
          conversationsDeleted: 7,
          logsPseudonymized: 89,
          auditEntriesPseudonymized: 23,
          failedSteps: [],
          complete: true,
        }),
      ),
    );
    renderPage();
    await runErasure();

    expect(screen.getByText("Schedules Deleted").closest("div")).toHaveTextContent("—");
  });

  // ─── Partial erasure (207 Multi-Status) ────────────────────────────────

  it("reports a partial erasure as incomplete and names the failed steps", async () => {
    // The counters are non-zero on a partial run too, so a panel headed
    // "Erasure Complete" reads as a success that merely deleted less. Article
    // 17 is not fulfilled while a step is outstanding, and the data subject is
    // the one person who cannot check.
    server.use(
      http.delete("*/admin/gdpr/:userId", ({ params }) =>
        HttpResponse.json(
          {
            userId: params.userId as string,
            memoriesDeleted: 14,
            conversationsDeleted: 7,
            logsPseudonymized: 0,
            auditEntriesPseudonymized: 0,
            failedSteps: ["auditLedger", "attachments"],
            complete: false,
          },
          { status: 207 },
        ),
      ),
    );
    renderPage();
    await runErasure();

    expect(screen.getByText("Erasure Incomplete")).toBeInTheDocument();
    expect(screen.queryByText("Erasure Complete")).not.toBeInTheDocument();
    const steps = screen.getByTestId("gdpr-failed-steps");
    expect(steps).toHaveTextContent("auditLedger");
    expect(steps).toHaveTextContent("attachments");
  });

  it("still reports a clean erasure as complete", async () => {
    // The other half of the pin: a page that always said "Incomplete" would
    // satisfy the test above.
    renderPage();
    await runErasure();

    expect(screen.getByText("Erasure Complete")).toBeInTheDocument();
    expect(screen.queryByTestId("gdpr-failed-steps")).not.toBeInTheDocument();
  });

  // ─── Export data flow ──────────────────────────────────────────────────

  it("enables export button when user ID is entered", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "user-456");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-export-btn")).not.toBeDisabled();
    });
  });

  it("export button text shows 'Export Data'", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-export-btn")).toHaveTextContent("Export Data");
  });

  it("delete button text shows 'Delete All Data'", () => {
    renderPage();
    expect(screen.getByTestId("gdpr-delete-btn")).toHaveTextContent("Delete All Data");
  });

  // ─── Export completeness (207 Multi-Status) ────────────────────────────

  /** Export `userId` and wait for the download to have been offered. */
  async function runExport(userId = "user-456") {
    const user = userEvent.setup();
    await user.type(screen.getByTestId("gdpr-user-id"), userId);
    await waitFor(() => {
      expect(screen.getByTestId("gdpr-export-btn")).not.toBeDisabled();
    });
    await user.click(screen.getByTestId("gdpr-export-btn"));
  }

  it("warns that an incomplete bundle is not a fulfilled Art. 15/20 answer", async () => {
    // EDDI answers 207 with `complete: false` on EVERY export today, because
    // four personal-data categories it erases as this user's data have no
    // exporter yet. A DPO handing this over as complete is making a claim the
    // data subject cannot check.
    renderPage();
    await runExport();

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-export-incomplete")).toBeInTheDocument();
    });
    expect(screen.getByTestId("gdpr-omitted-categories")).toHaveTextContent(
      "groupConversations",
    );
  });

  it("says nothing when the bundle really is complete", async () => {
    // Guards the other direction: a banner shown unconditionally would satisfy
    // the test above while crying wolf on a backend that has closed the gap.
    server.use(
      http.get("*/admin/gdpr/:userId/export", ({ params }) =>
        HttpResponse.json({
          userId: params.userId as string,
          memories: [],
          conversations: [],
          managedConversations: [],
          auditEntries: [],
          attachments: [],
          totalConversations: 0,
          conversationsTruncated: false,
          failedConversationIds: [],
          omittedCategories: [],
          complete: true,
        }),
      ),
    );
    renderPage();
    await runExport();

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-export-btn")).not.toBeDisabled();
    });
    expect(screen.queryByTestId("gdpr-export-incomplete")).not.toBeInTheDocument();
  });

  it("reports a truncated conversation list and unreadable conversations", async () => {
    server.use(
      http.get("*/admin/gdpr/:userId/export", ({ params }) =>
        HttpResponse.json(
          {
            userId: params.userId as string,
            memories: [],
            conversations: [],
            managedConversations: [],
            auditEntries: [],
            attachments: [],
            totalConversations: 4200,
            conversationsTruncated: true,
            failedConversationIds: ["conv-a", "conv-b"],
            omittedCategories: [],
            complete: false,
          },
          { status: 207 },
        ),
      ),
    );
    renderPage();
    await runExport();

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-conversations-truncated")).toHaveTextContent("4200");
    });
    expect(screen.getByTestId("gdpr-failed-conversations")).toHaveTextContent("2");
  });

  // ─── Restrict processing toggle ─────────────────────────────────────

  it("clicking restrict toggle triggers restrict mutation for non-restricted user", async () => {
    server.use(
      http.post("*/admin/gdpr/:userId/restrict", () => {
        return new HttpResponse(null, { status: 200 });
      })
    );

    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "active-user");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-restrict-toggle")).not.toBeDisabled();
    });

    await user.click(screen.getByTestId("gdpr-restrict-toggle"));

    // After clicking, the toggle should have been activated
    expect(screen.getByTestId("gdpr-restrict-toggle")).toBeInTheDocument();
  });

  // ─── Restricted user state ─────────────────────────────────────────────

  it("shows 'Processing Restricted' badge for restricted user", async () => {
    server.use(
      http.get("*/admin/gdpr/:userId/restrict", () => {
        return HttpResponse.json(true);
      })
    );

    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "restricted-user");

    await waitFor(() => {
      expect(screen.getByTestId("restriction-badge-restricted")).toBeInTheDocument();
      expect(screen.getByText("Processing Restricted")).toBeInTheDocument();
    });
  });

  it("shows 'Lift Restriction' label on toggle for restricted user", async () => {
    server.use(
      http.get("*/admin/gdpr/:userId/restrict", () => {
        return HttpResponse.json(true);
      })
    );

    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "restricted-user");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-restrict-toggle")).toHaveTextContent("Lift Restriction");
    });
  });

  it("clicking lift restriction triggers unrestrict mutation", async () => {
    server.use(
      http.get("*/admin/gdpr/:userId/restrict", () => {
        return HttpResponse.json(true);
      })
    );

    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "restricted-user");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-restrict-toggle")).not.toBeDisabled();
    });

    await user.click(screen.getByTestId("gdpr-restrict-toggle"));

    // Verify no crash
    expect(screen.getByTestId("gdpr-restrict-toggle")).toBeInTheDocument();
  });

  // ─── Erasure complete heading ─────────────────────────────────────────

  it("shows 'Erasure Complete' heading in results", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "user-123");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });
    await user.click(screen.getByTestId("gdpr-delete-btn"));

    await waitFor(() => {
      expect(screen.getByText(/Confirm Data Deletion/i)).toBeInTheDocument();
    });

    await user.click(screen.getByText(/Yes, Delete All Data/i));

    await waitFor(() => {
      expect(screen.getByText("Erasure Complete")).toBeInTheDocument();
    });
  });

  // ─── User ID input ────────────────────────────────────────────────────

  it("shows user ID label", () => {
    renderPage();
    expect(screen.getByText("User ID")).toBeInTheDocument();
  });

  it("shows confirm dialog description with user ID", async () => {
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");
    await user.type(input, "test-user-abc");

    await waitFor(() => {
      expect(screen.getByTestId("gdpr-delete-btn")).not.toBeDisabled();
    });
    await user.click(screen.getByTestId("gdpr-delete-btn"));

    await waitFor(() => {
      expect(screen.getByText(/test-user-abc/)).toBeInTheDocument();
    });
  });

  // ─── Restriction status could not be read ───────────────────────────────

  it("reports an unknown restriction status instead of 'Processing Active' when the check fails", async () => {
    server.use(
      http.get("*/admin/gdpr/:userId/restrict", () => {
        return HttpResponse.json({ message: "boom" }, { status: 500 });
      })
    );

    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("gdpr-user-id"), "test-user-abc");

    await waitFor(() => {
      expect(screen.getByTestId("restriction-badge-unknown")).toBeInTheDocument();
    });

    // The bug this pins: a failed check used to render the green "Processing
    // Active" badge, reporting an unknown state as a known-safe one.
    expect(
      screen.queryByTestId("restriction-badge-active"),
    ).not.toBeInTheDocument();
  });

  it("blocks the restriction toggle while the status is unknown", async () => {
    server.use(
      http.get("*/admin/gdpr/:userId/restrict", () => {
        return HttpResponse.json({ message: "boom" }, { status: 500 });
      })
    );

    renderPage();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("gdpr-user-id"), "test-user-abc");

    await waitFor(() => {
      expect(screen.getByTestId("restriction-badge-unknown")).toBeInTheDocument();
    });

    // Both the label and the action derive from `isRestricted`, so acting on an
    // unknown state could restrict a user the caller meant to unrestrict.
    expect(screen.getByTestId("gdpr-restrict-toggle")).toBeDisabled();
  });
});

describe("GDPR Privacy Admin Page — panels follow the user id", () => {
  it("clears the export warning when the user id changes", async () => {
    // Both panels name a specific user. Leaving the warning up while the id
    // below it changes attributes one person's incomplete bundle to another —
    // the same misreporting the 207 handling exists to stop.
    renderPage();
    const user = userEvent.setup();
    const input = screen.getByTestId("gdpr-user-id");

    await user.type(input, "user-a");
    await waitFor(() => {
      expect(screen.getByTestId("gdpr-export-btn")).not.toBeDisabled();
    });
    await user.click(screen.getByTestId("gdpr-export-btn"));
    await waitFor(() => {
      expect(screen.getByTestId("gdpr-export-incomplete")).toBeInTheDocument();
    });

    await user.type(input, "b");

    expect(screen.queryByTestId("gdpr-export-incomplete")).not.toBeInTheDocument();
  });
});
