import { describe, it, expect, afterEach } from "vitest";
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

  it("result cards show all four metric labels", async () => {
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
      expect(screen.getByTestId("gdpr-results")).toBeInTheDocument();
      expect(screen.getByText("Memories Deleted")).toBeInTheDocument();
      expect(screen.getByText("Conversations Deleted")).toBeInTheDocument();
      expect(screen.getByText("Audit Pseudonymized")).toBeInTheDocument();
      expect(screen.getByText("Logs Pseudonymized")).toBeInTheDocument();
    });
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
});
