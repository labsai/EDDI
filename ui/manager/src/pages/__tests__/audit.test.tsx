import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { AuditPage } from "@/pages/audit";
import userEvent from "@testing-library/user-event";

function renderAudit() {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <MemoryRouter initialEntries={["/manage/audit"]}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <AuditPage />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
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

  it("shows conversation ID input by default", () => {
    renderAudit();
    expect(screen.getByTestId("conversation-input")).toBeInTheDocument();
  });

  it("shows agent search mode when toggled", async () => {
    renderAudit();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("mode-agent"));
    expect(screen.getByTestId("agent-input")).toBeInTheDocument();
    expect(screen.getByTestId("version-input")).toBeInTheDocument();
  });

  it("auto-loads recent entries on mount (no initial empty state)", async () => {
    renderAudit();
    // The page auto-loads "recent" entries, so the audit-timeline should appear
    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });
  });

  it("loads entries after entering conversation ID and searching", async () => {
    renderAudit();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });
  });

  it("displays task type badges with correct labels", async () => {
    renderAudit();
    const user = userEvent.setup();
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
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("audit-timeline")).toBeInTheDocument();
    });

    // Find and expand LLM Detail
    const llmButtons = screen.getAllByTestId("expand-LLM Detail");
    expect(llmButtons.length).toBe(1); // Only langchain entry has LLM detail
    await userEvent.click(llmButtons[0]!);

    await waitFor(() => {
      expect(screen.getByText(/"compiled_prompt"/)).toBeInTheDocument();
    });
  });

  it("shows the summary strip after search", async () => {
    renderAudit();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("summary-strip")).toBeInTheDocument();
    });
  });

  // ─── Hardening: new features ───────────────────────────────

  it("renders recent entries button", () => {
    renderAudit();
    expect(screen.getByTestId("recent-entries-btn")).toBeInTheDocument();
  });

  it("renders export button", async () => {
    renderAudit();
    const btn = screen.getByTestId("export-btn");
    expect(btn).toBeInTheDocument();
    // After auto-load, it should become enabled
    await waitFor(() => {
      expect(btn).not.toBeDisabled();
    });
  });

  it("clicking Recent switches to conversation mode and triggers search", async () => {
    renderAudit();
    // Auto-load already sets conversation-input to 'recent'
    const input = screen.getByTestId("conversation-input") as HTMLInputElement;
    expect(input.value).toBe("recent");
  });

  it("shows auto-refresh toggle after search", async () => {
    renderAudit();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("auto-refresh-toggle")).toBeInTheDocument();
    });
  });

  it("export button becomes enabled after search returns results", async () => {
    renderAudit();
    const user = userEvent.setup();
    await user.type(screen.getByTestId("conversation-input"), "conv1");
    await user.click(screen.getByTestId("search-button"));

    await waitFor(() => {
      expect(screen.getByTestId("export-btn")).not.toBeDisabled();
    });
  });
});
