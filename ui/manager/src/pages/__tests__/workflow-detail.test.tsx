import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage, userEvent } from "@/test/test-utils";
import { WorkflowDetailPage } from "@/pages/workflow-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function renderWorkflow(id = "wf1", searchParams = "") {
  return renderPage(
    `/manage/workflowview/${id}${searchParams}`,
    <WorkflowDetailPage />,
    "/manage/workflowview/:id"
  );
}

describe("WorkflowDetailPage", () => {
  it("renders page heading", async () => {
    renderWorkflow();

    await waitFor(() => {
      // MSW returns descriptor name "Support Ticket Pipeline" for wf1
      expect(screen.getByText("Support Ticket Pipeline")).toBeInTheDocument();
    });
  });

  it("renders pipeline with extension items", async () => {
    renderWorkflow();

    await waitFor(() => {
      // MSW returns a 6-step pipeline (parser ×2 → rules → property → llm → output)
      expect(screen.getByText("Rules")).toBeInTheDocument();
      expect(screen.getByText("LLM")).toBeInTheDocument();
      expect(screen.getByText("Output")).toBeInTheDocument();
    });
  });

  it("renders extension pipeline section header", async () => {
    renderWorkflow();

    await waitFor(() => {
      expect(screen.getByText("Pipeline")).toBeInTheDocument();
    });
  });

  it("renders add extension button", async () => {
    renderWorkflow();

    await waitFor(() => {
      expect(screen.getByTestId("add-extension-btn")).toBeInTheDocument();
    });
  });

  it("renders save and discard buttons", async () => {
    renderWorkflow();

    await waitFor(() => {
      expect(screen.getByTestId("save-btn")).toBeInTheDocument();
      expect(screen.getByTestId("discard-btn")).toBeInTheDocument();
    });
  });

  it("renders delete button", async () => {
    renderWorkflow();

    await waitFor(() => {
      expect(screen.getByTestId("delete-wf-btn")).toBeInTheDocument();
    });
  });

  // ─── Back link ──────────────────────────────────────────────────────────

  it("renders back to workflows link when no agentId param", async () => {
    renderWorkflow();
    await waitFor(() => {
      expect(screen.getByText("Back to Workflows")).toBeInTheDocument();
    });
  });

  it("renders back to agent link when agentId param is present", async () => {
    renderWorkflow("wf1", "?agentId=agent1&agentVer=1");
    await waitFor(() => {
      expect(screen.getByText("Back to Agent")).toBeInTheDocument();
    });
  });

  // ─── Version info ──────────────────────────────────────────────────────

  it("shows version info", async () => {
    renderWorkflow();
    await waitFor(() => {
      const picker = screen.queryByTestId("version-picker");
      const badge = screen.queryByTestId("version-badge");
      expect(picker || badge).toBeTruthy();
    });
  });

  // ─── Workflow ID display ───────────────────────────────────────────────

  it("shows workflow ID in page header", async () => {
    renderWorkflow("wf1");
    await waitFor(() => {
      expect(screen.getByText("wf1")).toBeInTheDocument();
    });
  });

  // ─── Pipeline count badge ──────────────────────────────────────────────

  it("shows pipeline step count badge with correct count", async () => {
    renderWorkflow();
    await waitFor(() => {
      // Use data-testid to reliably find the pipeline count badge
      const countBadge = screen.getByTestId("pipeline-step-count");
      expect(countBadge).toHaveTextContent("6");
    });
  });

  // ─── Save/Discard disabled state ───────────────────────────────────────

  it("save button is disabled when no changes", async () => {
    renderWorkflow();
    await waitFor(() => {
      expect(screen.getByTestId("save-btn")).toBeDisabled();
    });
  });

  it("discard button is disabled when no changes", async () => {
    renderWorkflow();
    await waitFor(() => {
      expect(screen.getByTestId("discard-btn")).toBeDisabled();
    });
  });

  // ─── Add Task button text ──────────────────────────────────────────────

  it("add task button shows correct text", async () => {
    renderWorkflow();
    await waitFor(() => {
      expect(screen.getByText("Add Task")).toBeInTheDocument();
    });
  });

  // ─── Error state ───────────────────────────────────────────────────────

  it("shows error state when workflow API returns 500", async () => {
    server.use(
      http.get("*/workflowstore/workflows/:id", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );
    renderWorkflow("fail-workflow");
    await waitFor(() => {
      expect(screen.getByText("Something went wrong")).toBeInTheDocument();
    }, { timeout: 10000 });
  });

  it("shows retry button in error state", async () => {
    server.use(
      http.get("*/workflowstore/workflows/:id", () => {
        return HttpResponse.json({ error: "fail" }, { status: 500 });
      })
    );
    renderWorkflow("fail-workflow");
    await waitFor(() => {
      expect(screen.getByText("Retry")).toBeInTheDocument();
    }, { timeout: 10000 });
  });

  // ─── Delete dialog ────────────────────────────────────────────────────

  it("opens delete confirmation dialog", async () => {
    renderWorkflow();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("delete-wf-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-wf-btn"));

    await waitFor(() => {
      expect(screen.getByText(/This action cannot be undone/)).toBeInTheDocument();
    });
  });

  // ─── Raw config section ───────────────────────────────────────────────

  it("renders raw config section", async () => {
    renderWorkflow();
    await waitFor(() => {
      expect(screen.getByText("Raw Configuration")).toBeInTheDocument();
    });
  });

  it("expands raw config section when clicked", async () => {
    renderWorkflow();
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Raw Configuration")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Raw Configuration"));

    await waitFor(() => {
      // Should show a pre element with JSON
      const preElement = document.querySelector("pre");
      expect(preElement).toBeInTheDocument();
      expect(preElement?.textContent).toContain("workflowSteps");
    });
  });

  // ─── Save & Test button ───────────────────────────────────────────────

  it("shows Save & Test button when navigated from agent detail", async () => {
    renderWorkflow("wf1", "?agentId=agent1&agentVer=1");
    await waitFor(() => {
      expect(screen.getByTestId("save-test-btn")).toBeInTheDocument();
      expect(screen.getByText(/Save & Test/)).toBeInTheDocument();
    });
  });

  it("does not show Save & Test button without agent context", async () => {
    renderWorkflow("wf1");
    await waitFor(() => {
      expect(screen.getByTestId("save-btn")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("save-test-btn")).not.toBeInTheDocument();
  });

  // ─── Delete confirmation + mutation ──────────────────────────────────

  it("calls delete API after confirming in dialog", async () => {
    let deletedId = "";
    server.use(
      http.delete("*/workflowstore/workflows/:id", ({ params }) => {
        deletedId = params.id as string;
        return new HttpResponse(null, { status: 200 });
      })
    );
    renderWorkflow("wf1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("delete-wf-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("delete-wf-btn"));

    await waitFor(() => {
      expect(screen.getByText(/This action cannot be undone/)).toBeInTheDocument();
    });

    // Click the confirm delete button in the dialog
    const confirmBtn = screen.getByRole("button", { name: /delete/i });
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(deletedId).toBe("wf1");
    });
  });

  // ─── Loading state ───────────────────────────────────────────────────

  it("shows loading spinner while workflow data is loading", () => {
    server.use(
      http.get("*/workflowstore/workflows/:id", async () => {
        await new Promise((resolve) => setTimeout(resolve, 10000));
        return HttpResponse.json({});
      })
    );
    renderWorkflow("slow-wf");
    // Loading spinner should be visible via data-testid
    expect(screen.getByTestId("workflow-loading")).toBeInTheDocument();
  });

  // ─── Dirty indicator on extension removal ──────────────────────────

  it("shows dirty indicator when extensions are removed", async () => {
    renderWorkflow("wf1");
    const user = userEvent.setup();

    // Wait for pipeline to load
    await waitFor(() => {
      expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6");
    });

    // Find and click a remove button on a pipeline step
    const removeButtons = screen.getAllByTestId(/^remove-ext-/);
    if (removeButtons.length > 0) {
      await user.click(removeButtons[0]!);

      await waitFor(() => {
        expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
      });

      // Save button should now be enabled
      expect(screen.getByTestId("save-btn")).not.toBeDisabled();
      // Discard button should also be enabled
      expect(screen.getByTestId("discard-btn")).not.toBeDisabled();
    }
  });

  // ─── Discard changes ─────────────────────────────────────────────────

  it("discard button resets changes", async () => {
    renderWorkflow("wf1");
    const user = userEvent.setup();

    // Wait for pipeline to load
    await waitFor(() => {
      expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6");
    });

    // Remove a step to make dirty
    const removeButtons = screen.getAllByTestId(/^remove-ext-/);
    if (removeButtons.length > 0) {
      await user.click(removeButtons[0]!);

      await waitFor(() => {
        expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
      });

      // Click discard
      await user.click(screen.getByTestId("discard-btn"));

      // Dirty indicator should disappear
      await waitFor(() => {
        expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument();
      });

      // Pipeline count should be back to 6
      expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6");
    }
  });

  // ─── Save workflow ───────────────────────────────────────────────────

  it("calls save API when save button is clicked after changes", async () => {
    let saveCalled = false;
    server.use(
      http.put("*/workflowstore/workflows/:id", () => {
        saveCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: {
            Location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
          },
        });
      })
    );
    renderWorkflow("wf1");
    const user = userEvent.setup();

    // Wait for pipeline to load
    await waitFor(() => {
      expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6");
    });

    // Remove a step to make dirty
    const removeButtons = screen.getAllByTestId(/^remove-ext-/);
    if (removeButtons.length > 0) {
      await user.click(removeButtons[0]!);

      await waitFor(() => {
        expect(screen.getByTestId("save-btn")).not.toBeDisabled();
      });

      // Click save
      await user.click(screen.getByTestId("save-btn"));

      await waitFor(() => {
        expect(saveCalled).toBe(true);
      });
    }
  });
});

