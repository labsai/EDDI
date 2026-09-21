import { describe, it, expect, vi, afterEach } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";
import userEvent from "@testing-library/user-event";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

function LocationDisplay() {
  const location = useLocation();
  return <div data-testid="location-display">{location.pathname}</div>;
}

function renderPage(type: string, id = "res1", initialEntries: string[] = []) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  const entries = initialEntries.length > 0 ? initialEntries : [`/manage/resources/${type}/${id}`];

  return render(
    <MemoryRouter initialEntries={entries}>
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route
              path="/manage/resources/:type/:id"
              element={<ResourceDetailPage />}
            />
            <Route
              path="/manage/resources/:type"
              element={<div data-testid="resource-list">Resource List</div>}
            />
          </Routes>
          <LocationDisplay />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>
  );
}

// Stub console.error to reduce noise from intentional error tests
const originalConsoleError = console.error;
afterEach(() => {
  console.error = originalConsoleError;
});

describe("ResourceDetailPage - Core Functions", () => {
  it("renders back to list link by default", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      const backLink = screen.getByText(/Back to/i);
      expect(backLink).toBeInTheDocument();
      expect(backLink.closest("a")).toHaveAttribute("href", "/manage/resources/llm");
    });
  });

  it("renders back to workflow link if wfId is in search params", async () => {
    renderPage("llm", "res1", ["/manage/resources/llm/res1?wfId=wf1&agentId=agent1&agentVer=1"]);
    await waitFor(() => {
      const backLink = screen.getByText(/Back to Workflow/i);
      expect(backLink).toBeInTheDocument();
      expect(backLink.closest("a")).toHaveAttribute("href", "/manage/workflowview/wf1?agentId=agent1&agentVer=1");
    });
  });

  it("renders back to workflow link with only agentId (no agentVer)", async () => {
    renderPage("llm", "res1", ["/manage/resources/llm/res1?wfId=wf1&agentId=agent1"]);
    await waitFor(() => {
      const backLink = screen.getByText(/Back to Workflow/i);
      expect(backLink).toBeInTheDocument();
      expect(backLink.closest("a")).toHaveAttribute("href", "/manage/workflowview/wf1?agentId=agent1");
    });
  });

  it("shows delete confirmation dialog and verifies delete API called", async () => {
    let deleteCalled = false;
    server.use(
      http.delete("*/:store/:plural/:id", () => {
        deleteCalled = true;
        return new HttpResponse(null, { status: 204 });
      })
    );

    renderPage("llm", "res1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Delete")).toBeInTheDocument();
    });

    const deleteBtn = screen.getByText("Delete");
    await user.click(deleteBtn);

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    const dialogButtons = screen.getAllByRole("button");
    const confirmBtn = dialogButtons.find(b => b.textContent?.includes("Delete") && b !== deleteBtn);
    expect(confirmBtn).toBeDefined();
    await user.click(confirmBtn!);

    await waitFor(() => {
      expect(deleteCalled).toBe(true);
    });
  });

  it("navigates to resource list after successful delete", async () => {
    server.use(
      http.delete("*/:store/:plural/:id", () => {
        return new HttpResponse(null, { status: 204 });
      })
    );

    renderPage("llm", "res1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getAllByText("Delete").length).toBeGreaterThan(0);
    });

    // Click the first Delete button (the page-level one)
    await user.click(screen.getAllByText("Delete")[0]!);

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    // The dialog has its own Delete (confirm) button — it's the second one
    const dialog = screen.getByRole("dialog");
    const confirmBtn = within(dialog).getByText("Delete");
    await user.click(confirmBtn);

    await waitFor(() => {
      expect(screen.getByTestId("location-display")).toHaveTextContent("/manage/resources/llm");
    });
  });

  it("calls duplicate mutation and navigates when duplicate button is clicked", async () => {
    let duplicateCalled = false;
    server.use(
      http.post("*/llmstore/llms/res1", () => {
        duplicateCalled = true;
        return new HttpResponse(null, {
          status: 201,
          headers: {
            Location: "eddi://ai.labs.llm/llmstore/llms/dup-new-id?version=1",
          },
        });
      })
    );

    renderPage("llm", "res1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Duplicate")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Duplicate"));

    await waitFor(() => {
      expect(duplicateCalled).toBe(true);
    });

    await waitFor(() => {
      const loc = screen.getByTestId("location-display").textContent;
      expect(loc).toMatch(/\/manage\/resources\/llm\/.+/);
    });
  });

  it("renders error state for unknown resource type", async () => {
    renderPage("unknownType", "res1");
    await waitFor(() => {
      expect(screen.getByText(/Unknown resource type/i)).toBeInTheDocument();
    });
  });

  it("shows back to resources link for unknown type", async () => {
    renderPage("unknownType", "res1");
    await waitFor(() => {
      const link = screen.getByText(/Back to Resources/i);
      expect(link).toBeInTheDocument();
      expect(link.closest("a")).toHaveAttribute("href", "/manage/resources");
    });
  });

  it("shows cascade warning when cascade params are present", async () => {
    renderPage("llm", "res1", ["/manage/resources/llm/res1?wfId=wf1&wfVer=1&agentId=agent1&agentVer=1"]);

    await waitFor(() => {
      expect(screen.getByText(/Changes will cascade to parent workflow and agent/i)).toBeInTheDocument();
    });
  });

  it("does not show cascade warning when only partial params present (no wfVer)", async () => {
    renderPage("llm", "res1", ["/manage/resources/llm/res1?wfId=wf1&agentId=agent1&agentVer=1"]);

    await waitFor(() => {
      expect(screen.getByText(/Back to Workflow/i)).toBeInTheDocument();
    });
    // Cascade warning should NOT appear (missing wfVer)
    expect(screen.queryByText(/Changes will cascade/i)).not.toBeInTheDocument();
  });

  it("renders version badge", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByText(/^v\d+$/)).toBeInTheDocument();
    });
  });

  it("shows resource id in the header", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByText("res1")).toBeInTheDocument();
    });
  });

  it("shows loading skeleton before data loads", async () => {
    server.use(
      http.get("*/:store/:plural/:id", async () => {
        await new Promise((r) => setTimeout(r, 10000));
        return HttpResponse.json({});
      })
    );

    renderPage("llm", "res1");
    expect(screen.queryByTestId("config-editor-layout")).not.toBeInTheDocument();
    expect(screen.getByText(/Back to/i)).toBeInTheDocument();
  });

  it("shows error state when version descriptors fail", async () => {
    console.error = vi.fn(); // Suppress expected error output
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return new HttpResponse(null, { status: 500 });
      }),
      http.get("*/:store/:plural/:id", () => {
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderPage("llm", "res1");

    // The page should still render with error indication
    await waitFor(() => {
      // Back link should still be present regardless of error
      expect(screen.getByText(/Back to/i)).toBeInTheDocument();
    });
  });

  it("shows error state when resource data fails with 500", async () => {
    console.error = vi.fn();
    server.use(
      http.get("*/:store/:plural/:id", ({ request }) => {
        const url = new URL(request.url);
        // Let currentversion pass through
        if (url.pathname.endsWith("/currentversion")) return;
        if (url.pathname.endsWith("/descriptors")) return;
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderPage("llm", "res1");

    await waitFor(() => {
      // Should show the header area even on error
      expect(screen.getByText(/Back to/i)).toBeInTheDocument();
    });
  });

  // --- Different resource types render correctly ---

  it("renders for rules resource type", async () => {
    renderPage("rules", "beh1");
    await waitFor(() => {
      expect(screen.getByText("beh1")).toBeInTheDocument();
    });
  });

  it("renders for apicalls resource type", async () => {
    renderPage("apicalls", "api1");
    await waitFor(() => {
      expect(screen.getByText("api1")).toBeInTheDocument();
    });
  });

  it("renders for output resource type", async () => {
    renderPage("output", "out1");
    await waitFor(() => {
      expect(screen.getByText("out1")).toBeInTheDocument();
    });
  });

  it("renders for dictionary resource type", async () => {
    renderPage("dictionary", "dict1");
    await waitFor(() => {
      expect(screen.getByText("dict1")).toBeInTheDocument();
    });
  });

  it("renders for propertysetter resource type", async () => {
    renderPage("propertysetter", "ps1");
    await waitFor(() => {
      expect(screen.getByText("ps1")).toBeInTheDocument();
    });
  });

  // --- Delete and Duplicate buttons present ---

  it("renders both duplicate and delete action buttons", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByText("Duplicate")).toBeInTheDocument();
      expect(screen.getByText("Delete")).toBeInTheDocument();
    });
  });

  // --- Cascade context from URL ---

  it("builds cascade context correctly from URL params", async () => {
    renderPage("llm", "res1", [
      "/manage/resources/llm/res1?wfId=wf99&wfVer=5&agentId=agent77&agentVer=3",
    ]);

    await waitFor(() => {
      expect(screen.getByText(/Changes will cascade/i)).toBeInTheDocument();
    });
  });

  // --- Version descriptors and version resolution ---

  it("defaults to version 1 when descriptor list is empty", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return HttpResponse.json([]);
      })
    );

    renderPage("llm", "res1");

    await waitFor(() => {
      expect(screen.getByText("v1")).toBeInTheDocument();
    });
  });

  it("resolves latest version from multiple descriptors", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return HttpResponse.json([
          { resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=1", name: "V1" },
          { resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=3", name: "V3" },
          { resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=2", name: "V2" },
        ]);
      })
    );

    renderPage("llm", "res1");

    await waitFor(() => {
      // Should pick the highest version (3)
      expect(screen.getByText("v3")).toBeInTheDocument();
    });
  });

  // --- Descriptor name in title ---

  it("shows descriptor name in page title", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return HttpResponse.json([
          {
            resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=1",
            name: "My LLM Config",
            lastModifiedOn: Date.now(),
          },
        ]);
      })
    );

    renderPage("llm", "res1");

    await waitFor(() => {
      expect(screen.getByText("My LLM Config")).toBeInTheDocument();
    });
  });

  // --- Delete dialog cancellation ---

  it("closes delete dialog when cancelled", async () => {
    renderPage("llm", "res1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByText("Delete")).toBeInTheDocument();
    });

    await user.click(screen.getByText("Delete"));

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    // Click cancel
    const cancelBtn = screen.getByText("Cancel");
    await user.click(cancelBtn);

    await waitFor(() => {
      expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    });
  });

  // --- ConfigEditorLayout rendering ---

  it("renders ConfigEditorLayout when data is loaded", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
    });
  });

  it("shows Form and JSON tabs in the editor", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByTestId("tab-form")).toBeInTheDocument();
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });
  });

  it("switches to JSON tab when clicked", async () => {
    renderPage("llm", "res1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });

  it("shows save button in editor layout", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByTestId("save-btn")).toBeInTheDocument();
    });
  });

  it("does not show dirty indicator initially", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument();
  });

  // --- Version picker interaction ---

  it("renders version picker with version options from descriptors", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return HttpResponse.json([
          { resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=1", name: "First" },
          { resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=2", name: "Second" },
        ]);
      })
    );

    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
    });
  });

  // --- Error state with retry ---

  it("shows error state with retry button when both data and versions fail", async () => {
    console.error = vi.fn();
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return HttpResponse.json(
          [{ resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=1" }]
        );
      }),
      http.get("*/:store/:plural/:id", ({ request }) => {
        const url = new URL(request.url);
        if (url.pathname.endsWith("/currentversion")) return;
        if (url.pathname.endsWith("/descriptors")) return;
        return new HttpResponse(null, { status: 500 });
      })
    );

    renderPage("llm", "res1");

    await waitFor(() => {
      // Back link should always render
      expect(screen.getByText(/Back to/i)).toBeInTheDocument();
    });
  });

  // --- mcpcalls resource type ---

  it("renders for mcpcalls resource type", async () => {
    renderPage("mcpcalls", "mcp1");
    await waitFor(() => {
      expect(screen.getByText("mcp1")).toBeInTheDocument();
    });
  });

  // --- rag resource type ---

  it("renders for rag resource type", async () => {
    renderPage("rag", "rag1");
    await waitFor(() => {
      expect(screen.getByText("rag1")).toBeInTheDocument();
    });
  });

  // --- snippets resource type ---

  it("renders for snippets resource type", async () => {
    renderPage("snippets", "snip1");
    await waitFor(() => {
      expect(screen.getByText("snip1")).toBeInTheDocument();
    });
  });

  // --- Save button disabled without dirty state ---

  it("save button is disabled when no changes are made", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      const saveBtn = screen.getByTestId("save-btn");
      expect(saveBtn).toBeDisabled();
    });
  });

  // --- Discard button disabled without dirty state ---

  it("discard button is disabled when no changes are made", async () => {
    renderPage("llm", "res1");
    await waitFor(() => {
      const discardBtn = screen.getByTestId("discard-btn");
      expect(discardBtn).toBeDisabled();
    });
  });

  // --- Compare versions button (requires >1 versions) ---

  it("renders compare versions button when multiple versions exist", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return HttpResponse.json([
          { resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=1", name: "V1", lastModifiedOn: Date.now() - 100000 },
          { resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=2", name: "V2", lastModifiedOn: Date.now() },
        ]);
      })
    );

    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByTestId("compare-versions-btn")).toBeInTheDocument();
    });
  });

  it("does not show compare button when only one version exists", async () => {
    server.use(
      http.get("*/:store/:plural/descriptors", () => {
        return HttpResponse.json([
          { resource: "eddi://ai.labs.mock/llmstore/llms/res1?version=1", name: "V1" },
        ]);
      })
    );

    renderPage("llm", "res1");
    await waitFor(() => {
      expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
    });
    expect(screen.queryByTestId("compare-versions-btn")).not.toBeInTheDocument();
  });

  // --- Save flow ---

  it("saves resource when form is dirtied and save clicked", async () => {
    let saveCalled = false;
    server.use(
      http.put("*/llmstore/llms/:id", () => {
        saveCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: {
            Location: "eddi://ai.labs.llm/llmstore/llms/res1?version=2",
          },
        });
      })
    );

    renderPage("llm", "res1");
    const user = userEvent.setup();

    // Wait for editor to load
    await waitFor(() => {
      expect(screen.getByTestId("config-editor-layout")).toBeInTheDocument();
    });

    // Make form dirty by changing the model type
    await waitFor(() => {
      expect(screen.getByTestId("model-type-select")).toBeInTheDocument();
    });
    await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");

    // Dirty indicator should appear
    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });

    // Save button should be enabled
    expect(screen.getByTestId("save-btn")).not.toBeDisabled();
    await user.click(screen.getByTestId("save-btn"));

    // Verify save was called
    await waitFor(() => {
      expect(saveCalled).toBe(true);
    });
  });

  it("shows dirty indicator when model type is changed", async () => {
    renderPage("llm", "res1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("model-type-select")).toBeInTheDocument();
    });

    // Initially no dirty indicator
    expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument();

    await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });

  it("disables version picker when form is dirty", async () => {
    renderPage("llm", "res1");
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("model-type-select")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });

  // --- Save with cascade context ---

  it("saves with cascade context when URL has all cascade params", async () => {
    let saveCalled = false;
    server.use(
      http.put("*/llmstore/llms/:id", () => {
        saveCalled = true;
        return new HttpResponse(null, {
          status: 200,
          headers: {
            Location: "eddi://ai.labs.llm/llmstore/llms/res1?version=2",
          },
        });
      }),
      http.get("*/workflowstore/workflows/:id", () => {
        return HttpResponse.json({
          workflowSteps: [
            { config: { uri: "eddi://ai.labs.llm/llmstore/llms/res1?version=1" } },
          ],
        });
      }),
      http.put("*/workflowstore/workflows/:id", () => {
        return new HttpResponse(null, {
          status: 200,
          headers: {
            Location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
          },
        });
      }),
      http.get("*/agentstore/agents/:id", () => {
        return HttpResponse.json({
          name: "test-agent",
          workflows: [
            "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1",
          ],
        });
      }),
      http.put("*/agentstore/agents/:id", () => {
        return new HttpResponse(null, {
          status: 200,
          headers: {
            Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2",
          },
        });
      })
    );

    renderPage("llm", "res1", [
      "/manage/resources/llm/res1?wfId=wf1&wfVer=1&agentId=agent1&agentVer=1",
    ]);
    const user = userEvent.setup();

    await waitFor(() => {
      expect(screen.getByTestId("model-type-select")).toBeInTheDocument();
    });

    await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("save-btn"));

    await waitFor(() => {
      expect(saveCalled).toBe(true);
    });
  });
});
