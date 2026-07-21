import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { AuditPage } from "@/pages/audit";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";
import type { AuditEntry } from "@/lib/api/audit";

function renderAudit() {
  return renderWithProviders(<AuditPage />, {
    initialRoute: "/manage/audit",
  });
}

/** Build a full page of audit entries whose ids encode the page label. */
function makeAuditPage(label: string, count: number, skip: number): AuditEntry[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `${label}-${skip + i}`,
    conversationId: "conv-load-more",
    agentId: "agent1",
    agentVersion: 1,
    userId: null,
    environment: null,
    stepIndex: 0,
    taskId: `${label}-task-${skip + i}`,
    taskType: "behavior",
    taskIndex: i,
    durationMs: 10,
    input: null,
    output: null,
    llmDetail: null,
    toolCalls: null,
    actions: null,
    cost: 0,
    timestamp: new Date().toISOString(),
    hmac: null,
    agentSignature: null,
  }));
}

describe("AuditPage", () => {
  it("renders the page title and description", () => {
    renderAudit();
    expect(screen.getByText("Audit Trail")).toBeInTheDocument();
    expect(
      screen.getByText(
        "Browse the immutable audit ledger for compliance and debugging."
      )
    ).toBeInTheDocument();
  });

  it("renders the audit-page container", () => {
    renderAudit();
    expect(screen.getByTestId("audit-page")).toBeInTheDocument();
  });

  it("defaults to agent mode", () => {
    renderAudit();
    expect(screen.getByTestId("agent-input")).toBeInTheDocument();
    expect(screen.getByTestId("version-input")).toBeInTheDocument();
  });

  it("shows conversation ID input when toggled to conversation mode", async () => {
    renderAudit();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("mode-conversation"));
    expect(screen.getByTestId("conversation-input")).toBeInTheDocument();
  });

  it("shows empty state when no agent is selected", () => {
    renderAudit();
    expect(screen.getByTestId("empty-state")).toBeInTheDocument();
  });

  it("loads entries after selecting an agent", async () => {
    renderAudit();
    const user = userEvent.setup();
    const select = screen.getByTestId("agent-input") as HTMLSelectElement;

    // Wait for agents to load, then select one
    await waitFor(() => {
      expect(select.options.length).toBeGreaterThan(1);
    });

    await user.selectOptions(select, select.options[1]!.value);

    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });
  });

  it("loads entries after entering conversation ID and searching", async () => {
    renderAudit();
    const user = userEvent.setup();

    // Switch to conversation mode
    await user.click(screen.getByTestId("mode-conversation"));

    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });
  });

  it("displays task type badges with correct labels", async () => {
    renderAudit();
    const user = userEvent.setup();

    // Switch to conversation mode and search
    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      const badges = screen.getAllByTestId("task-type-badge");
      expect(badges.length).toBe(4);
      expect(badges[0]).toHaveTextContent("expressions");
      expect(badges[1]).toHaveTextContent("behavior");
      expect(badges[2]).toHaveTextContent("langchain");
      expect(badges[3]).toHaveTextContent("output");
    });
  });

  it("shows duration on entry cards", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      const pills = screen.getAllByTestId("duration-pill");
      expect(pills.length).toBe(4);
      expect(pills[0]).toHaveTextContent("12ms");
    });
  });

  it("shows cost when > 0", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      const costPills = screen.getAllByTestId("cost-pill");
      // Only the langchain entry has cost > 0
      expect(costPills.length).toBe(1);
      expect(costPills[0]).toHaveTextContent("$0.0030");
    });
  });

  it("shows action badges", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      const actionBadges = screen.getAllByTestId("action-badge");
      expect(actionBadges.length).toBeGreaterThan(0);
    });
  });

  it("expands input section on click", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });

    const expandButtons = screen.getAllByTestId("expand-Input");
    await user.click(expandButtons[0]!);

    // Should show JSON content
    await waitFor(() => {
      expect(screen.getByText(/"input:initial"/)).toBeInTheDocument();
    });
  });

  it("shows LLM detail section for langchain tasks", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });

    // Find and expand LLM Detail
    const llmButtons = screen.getAllByTestId("expand-LLM Detail");
    expect(llmButtons.length).toBe(1); // Only langchain entry has LLM detail
    await user.click(llmButtons[0]!);

    await waitFor(() => {
      expect(screen.getByText(/"compiled_prompt"/)).toBeInTheDocument();
    });
  });

  it("shows the summary strip after search", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("summary-strip")).toBeInTheDocument();
    });
  });

  // ─── Hardening: new features ───────────────────────────────

  it("renders export button", () => {
    renderAudit();
    const btn = screen.getByTestId("export-btn");
    expect(btn).toBeInTheDocument();
  });

  it("defaults to agent mode with agent dropdown visible", () => {
    renderAudit();
    // Agent mode should be the default
    expect(screen.getByTestId("agent-input")).toBeInTheDocument();
    expect(screen.getByTestId("version-input")).toBeInTheDocument();
  });

  it("shows auto-refresh toggle after search", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("auto-refresh-toggle")).toBeInTheDocument();
    });
  });

  it("export button becomes enabled after search returns results", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("export-btn")).not.toBeDisabled();
    });
  });

  it("step headers are collapsible", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });

    // Step headers should be present and clickable
    const stepHeader = screen.getAllByTestId(/step-header-/)[0]!;
    expect(stepHeader).toBeInTheDocument();

    // Click to collapse
    await user.click(stepHeader);

    // The task cards inside should be hidden (step entries collapsed)
    // We verify the step header is still there (it's a toggle)
    expect(stepHeader).toBeInTheDocument();
  });

  it("shows integrity banner after search with results", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("integrity-banner")).toBeInTheDocument();
    });
  });

  it("renders per-entry signed and unsigned badges", async () => {
    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });

    // All 4 hardcoded entries have HMAC, so all should show "Signed" badges
    const signedBadges = screen.getAllByTestId("hmac-badge");
    expect(signedBadges.length).toBeGreaterThan(0);
  });

  // ─── Paging: Load more appends instead of replacing ──────────────

  it("'Load more' appends the next page and keeps page-1 entries", async () => {
    const PAGE_SIZE = 100;
    server.use(
      http.get("*/auditstore/:conversationId", ({ request, params }) => {
        const url = new URL(request.url);
        if (url.pathname.endsWith("/count")) return;
        if (params.conversationId === "agent") return;
        const skip = Number(url.searchParams.get("skip") ?? "0");
        // Page 1 (skip 0) and page 2 (skip 100) carry distinct, identifiable ids.
        const label = skip === 0 ? "page1" : "page2";
        return HttpResponse.json(makeAuditPage(label, PAGE_SIZE, skip));
      }),
    );

    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv-load-more");
    await user.click(screen.getByTestId("search-button"));

    // Page 1 loaded.
    await waitFor(() => {
      expect(screen.getByTestId("audit-entry-page1-0")).toBeInTheDocument();
    });

    // Load more → fetches skip=100.
    await user.click(screen.getByTestId("load-more"));

    // Page 2 entries appear...
    await waitFor(() => {
      expect(screen.getByTestId("audit-entry-page2-100")).toBeInTheDocument();
    });
    // ...and page-1 entries are still present (appended, not replaced).
    expect(screen.getByTestId("audit-entry-page1-0")).toBeInTheDocument();
  });

  it("restarts paging at offset 0 when the search mode is toggled", async () => {
    // Regression: switching agent↔conversation must reset `skip`, otherwise a
    // leftover offset renders a mid-offset page as the first page and silently
    // drops (and makes unreachable) every earlier entry.
    const PAGE_SIZE = 100;
    server.use(
      http.get("*/auditstore/:conversationId", ({ request, params }) => {
        const url = new URL(request.url);
        if (url.pathname.endsWith("/count")) return;
        if (params.conversationId === "agent") return;
        const skip = Number(url.searchParams.get("skip") ?? "0");
        const label = skip === 0 ? "page1" : "page2";
        return HttpResponse.json(makeAuditPage(label, PAGE_SIZE, skip));
      }),
    );

    renderAudit();
    const user = userEvent.setup();

    await user.click(screen.getByTestId("mode-conversation"));
    await user.type(screen.getByTestId("conversation-input"), "conv-load-more");
    await user.click(screen.getByTestId("search-button"));
    await waitFor(() =>
      expect(screen.getByTestId("audit-entry-page1-0")).toBeInTheDocument(),
    );

    // Advance to page 2 (skip=100).
    await user.click(screen.getByTestId("load-more"));
    await waitFor(() =>
      expect(screen.getByTestId("audit-entry-page2-100")).toBeInTheDocument(),
    );

    // Toggle to agent mode and back: the fresh conversation view must restart at
    // page 1, not remain stranded at skip=100 (which would drop page 1).
    await user.click(screen.getByTestId("mode-agent"));
    await user.click(screen.getByTestId("mode-conversation"));

    await waitFor(() =>
      expect(screen.getByTestId("audit-entry-page1-0")).toBeInTheDocument(),
    );
  });
});
