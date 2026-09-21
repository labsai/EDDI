import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { TriggersPage } from "@/pages/triggers";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderPage() {
  return renderWithProviders(<TriggersPage />);
}

describe("TriggersPage", () => {
  it("renders the page container", () => {
    renderPage();
    expect(screen.getByTestId("triggers-page")).toBeInTheDocument();
  });

  it("renders the page title", () => {
    renderPage();
    expect(screen.getByText("Agent Triggers")).toBeInTheDocument();
  });

  it("renders the create button", () => {
    renderPage();
    expect(screen.getByTestId("create-trigger-btn")).toBeInTheDocument();
  });

  it("shows trigger list with mock data", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("trigger-list")).toBeInTheDocument();
      expect(screen.getByTestId("trigger-booking_request")).toBeInTheDocument();
      expect(screen.getByTestId("trigger-faq_query")).toBeInTheDocument();
      expect(screen.getByTestId("trigger-escalation")).toBeInTheDocument();
    });
  });

  it("shows agent count per trigger", async () => {
    renderPage();
    await waitFor(() => {
      // booking_request (2), escalation (2), product_recommendation (2), onboarding_start (2) show "2 agents"
      const twoCounts = screen.getAllByText("2 agents");
      expect(twoCounts.length).toBeGreaterThanOrEqual(1);
      // faq_query, payment_issue, contract_review, password_reset show "1 agents"
      const oneCounts = screen.getAllByText("1 agents");
      expect(oneCounts.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("opens create dialog when create button is clicked", async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-trigger-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("trigger-dialog")).toBeInTheDocument();
      expect(screen.getByText("Create Trigger")).toBeInTheDocument();
    });
  });

  it("shows intent input and deployments in dialog", async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-trigger-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("trigger-intent-input")).toBeInTheDocument();
      expect(screen.getByText("Agent Deployments")).toBeInTheDocument();
    });
  });

  it("shows search input when triggers exist", async () => {
    renderPage();
    await waitFor(() => {
      expect(screen.getByTestId("trigger-search")).toBeInTheDocument();
    });
  });

  it("filters triggers by search text", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("trigger-list")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("trigger-search"), "faq");

    await waitFor(() => {
      expect(screen.getByTestId("trigger-faq_query")).toBeInTheDocument();
      expect(screen.queryByTestId("trigger-booking_request")).not.toBeInTheDocument();
    });
  });

  it("closes dialog on Escape key", async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-trigger-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("trigger-dialog")).toBeInTheDocument();
    });

    await user.keyboard("{Escape}");

    await waitFor(() => {
      expect(screen.queryByTestId("trigger-dialog")).not.toBeInTheDocument();
    });
  });

  // ─── Subtitle ──────────────────────────────────────────────────────────

  it("renders the page subtitle", () => {
    renderPage();
    expect(screen.getByText(/Map intents to agent deployments/)).toBeInTheDocument();
  });

  // ─── Error state ───────────────────────────────────────────────────────

  it("shows error state when API fails", async () => {
    server.use(
      http.get("*/AgentTriggerStore/agenttriggers", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    });
  });

  it("shows retry button in error state", async () => {
    server.use(
      http.get("*/AgentTriggerStore/agenttriggers", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    });
  });

  // ─── Empty state ───────────────────────────────────────────────────────

  it("shows empty state when no triggers exist", async () => {
    server.use(
      http.get("*/AgentTriggerStore/agenttriggers", () => {
        return HttpResponse.json([]);
      })
    );
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("No triggers configured yet")).toBeInTheDocument();
    });
  });

  it("shows no results empty state when search matches nothing", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("trigger-list")).toBeInTheDocument();
    });

    await user.type(screen.getByTestId("trigger-search"), "zzzzzznonexistent");

    await waitFor(() => {
      expect(screen.getByText(/No results found/i)).toBeInTheDocument();
    });
  });

  // ─── Trigger expand/collapse ────────────────────────────────────────────

  it("expands a trigger card to show agent deployments", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("trigger-booking_request")).toBeInTheDocument();
    });

    // Click the expand button — use within() to find by aria-expanded
    const triggerCard = screen.getByTestId("trigger-booking_request");
    const expandBtn = within(triggerCard).getByRole("button", { expanded: false });
    await user.click(expandBtn);

    // Deployment details should appear — environment badge visible
    await waitFor(() => {
      expect(within(triggerCard).getByText("production")).toBeInTheDocument();
    });
  });

  // ─── Edit dialog ────────────────────────────────────────────────────────

  it("opens edit trigger dialog when edit button is clicked", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("trigger-booking_request")).toBeInTheDocument();
    });

    // Use within() to find edit button by accessible label
    const triggerCard = screen.getByTestId("trigger-booking_request");
    const editBtn = within(triggerCard).getByTitle("Edit");
    await user.click(editBtn);

    await waitFor(() => {
      expect(screen.getByTestId("trigger-dialog")).toBeInTheDocument();
      expect(screen.getByText("Edit Trigger")).toBeInTheDocument();
    });
  });

  it("intent field is readonly in edit mode", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("trigger-booking_request")).toBeInTheDocument();
    });

    const triggerCard = screen.getByTestId("trigger-booking_request");
    const editBtn = within(triggerCard).getByTitle("Edit");
    await user.click(editBtn);

    await waitFor(() => {
      const intentInput = screen.getByTestId("trigger-intent-input") as HTMLInputElement;
      expect(intentInput.readOnly).toBe(true);
    });
  });

  // ─── Delete dialog ──────────────────────────────────────────────────────

  it("opens delete confirmation dialog when delete button is clicked", async () => {
    renderPage();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("trigger-booking_request")).toBeInTheDocument();
    });

    const triggerCard = screen.getByTestId("trigger-booking_request");
    const deleteBtn = within(triggerCard).getByTitle("Delete");
    await user.click(deleteBtn);

    await waitFor(() => {
      expect(screen.getByText("Delete Trigger")).toBeInTheDocument();
    });
  });

  // ─── Search hides when no triggers ──────────────────────────────────────

  it("hides search when triggers list is empty", async () => {
    server.use(
      http.get("*/AgentTriggerStore/agenttriggers", () => {
        return HttpResponse.json([]);
      })
    );
    renderPage();

    await waitFor(() => {
      expect(screen.getByText("No triggers configured yet")).toBeInTheDocument();
    });

    expect(screen.queryByTestId("trigger-search")).not.toBeInTheDocument();
  });

  // ─── Save button disabled without intent ────────────────────────────────

  it("save button is disabled when intent is empty in create dialog", async () => {
    renderPage();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("create-trigger-btn"));

    await waitFor(() => {
      expect(screen.getByTestId("trigger-save-btn")).toBeDisabled();
    });
  });
});
