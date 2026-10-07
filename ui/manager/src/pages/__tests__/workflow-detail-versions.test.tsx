import { describe, expect, it, vi, beforeEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { createTestQueryClient, userEvent } from "@/test/test-utils";
import { WorkflowDetailPage } from "@/pages/workflow-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

const toastSuccess = vi.fn();
const toastError = vi.fn();
vi.mock("sonner", () => ({
  toast: Object.assign(vi.fn(), {
    success: (...args: unknown[]) => toastSuccess(...args),
    error: (...args: unknown[]) => toastError(...args),
    warning: vi.fn(),
  }),
}));

function Probe() {
  const location = useLocation();
  return <div data-testid="location">{location.pathname + location.search}</div>;
}

function renderWorkflow(search = "") {
  const queryClient = createTestQueryClient();
  return {
    ...render(
      <MemoryRouter initialEntries={[`/manage/workflowview/wf1${search}`]}>
        <QueryClientProvider client={queryClient}>
          <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
            <Routes>
              <Route path="/manage/workflowview/:id" element={<WorkflowDetailPage />} />
              <Route path="*" element={null} />
            </Routes>
            {/* Outside the routes, so it survives a navigation away from the page. */}
            <Probe />
          </ThemeProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    ),
    queryClient,
  };
}

/** An agent that references wf1 at the given version. */
function agentPinning(version: number) {
  return http.get("*/agentstore/agents/:id", () =>
    HttpResponse.json({
      workflows: [`eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=${version}`],
      channels: [],
    }),
  );
}

async function removeFirstStep() {
  await waitFor(() => expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6"));
  await userEvent.setup().click(screen.getAllByTestId(/^remove-ext-/)[0]!);
  await waitFor(() => expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument());
}

describe("WorkflowDetailPage — versions and the agent", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("opens the workflow version the link names, not the newest", async () => {
    renderWorkflow("?version=1&agentId=agent1&agentVer=3");
    // wf1's descriptors only know version 2; the link says 1.
    await waitFor(() => expect(screen.getByTestId("version-badge")).toHaveTextContent("v1"));
  });

  it("warns that the agent pins another version, and says what each fix does", async () => {
    server.use(agentPinning(1));
    renderWorkflow("?version=2&agentId=agent1&agentVer=3");
    const banner = await screen.findByTestId("agent-pin-mismatch");
    expect(banner).toHaveTextContent("version 1");
    expect(screen.getByTestId("agent-pin-switch")).toBeInTheDocument();
    expect(screen.getByTestId("agent-pin-update")).toBeInTheDocument();
  });

  it("shows no warning when the agent uses the version on screen", async () => {
    renderWorkflow("?version=2&agentId=agent1&agentVer=3");
    await waitFor(() => expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6"));
    await screen.findByTestId("save-test-btn");
    expect(screen.queryByTestId("agent-pin-mismatch")).not.toBeInTheDocument();
  });

  it("refuses to save, before writing anything, when the agent would not change", async () => {
    const writes: string[] = [];
    server.use(
      agentPinning(1),
      http.put("*/workflowstore/workflows/:id", () => {
        writes.push("workflow");
        return new HttpResponse(null, { status: 200 });
      }),
      http.put("*/agentstore/agents/:id", () => {
        writes.push("agent");
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderWorkflow("?version=2&agentId=agent1&agentVer=3");
    await removeFirstStep();
    await userEvent.setup().click(screen.getByTestId("save-btn"));

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(String(toastError.mock.calls[0]![0])).toContain("Nothing was saved");
    expect(writes).toEqual([]);
  });

  it("Save & Test refuses the same way instead of deploying an unchanged agent", async () => {
    const writes: string[] = [];
    server.use(
      agentPinning(1),
      http.put("*/workflowstore/workflows/:id", () => {
        writes.push("workflow");
        return new HttpResponse(null, { status: 200 });
      }),
      http.put("*/agentstore/agents/:id", () => {
        writes.push("agent");
        return new HttpResponse(null, { status: 200 });
      }),
      http.post("*/administration/*/deploy/*/*", () => {
        writes.push("deploy");
        return new HttpResponse(null, { status: 202 });
      }),
    );
    renderWorkflow("?version=2&agentId=agent1&agentVer=3");
    await removeFirstStep();
    await userEvent.setup().click(screen.getByTestId("save-test-btn"));

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(writes).toEqual([]);
  });

  it("cascades a plain Save into the agent, and says the result is not live", async () => {
    let agentBody: { workflows?: string[] } | undefined;
    server.use(
      http.put("*/agentstore/agents/:id", async ({ request, params }) => {
        agentBody = (await request.json()) as { workflows?: string[] };
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: `eddi://ai.labs.agent/agentstore/agents/${params.id}?version=4` },
        });
      }),
    );
    renderWorkflow("?version=2&agentId=agent1&agentVer=3");
    await removeFirstStep();
    await userEvent.setup().click(screen.getByTestId("save-btn"));

    await waitFor(() => expect(agentBody).toBeDefined());
    expect(agentBody!.workflows).toEqual([
      "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=3",
    ]);
    await waitFor(() => expect(toastSuccess).toHaveBeenCalled());
    expect(String(toastSuccess.mock.calls[0]![0])).toContain("v4");
  });

  it("a plain Save without an agent context writes only the workflow", async () => {
    let agentWrites = 0;
    server.use(
      http.put("*/agentstore/agents/:id", () => {
        agentWrites++;
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderWorkflow();
    await removeFirstStep();
    await userEvent.setup().click(screen.getByTestId("save-btn"));
    await waitFor(() => expect(screen.getByTestId("save-feedback")).toHaveTextContent(/saved/i));
    expect(agentWrites).toBe(0);
  });

  it("names the real reason a save failed instead of a generic message", async () => {
    server.use(
      http.put("*/workflowstore/workflows/:id", () =>
        HttpResponse.json({ error: "Version conflict: workflow was changed" }, { status: 409 }),
      ),
    );
    renderWorkflow();
    await removeFirstStep();
    await userEvent.setup().click(screen.getByTestId("save-btn"));
    await waitFor(() => expect(screen.getByTestId("save-feedback")).toBeInTheDocument());
    expect(screen.getByTestId("save-feedback")).not.toHaveTextContent("Failed to save workflow");
  });
});

describe("WorkflowDetailPage — a step added in this session", () => {
  async function addExistingLlm() {
    const user = userEvent.setup();
    await waitFor(() => expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6"));
    await user.click(screen.getByTestId("add-extension-btn"));
    await user.click(await screen.findByTestId("ext-option-eddi://ai.labs.llm"));
    const existing = await screen.findAllByTestId(/^existing-resource-/);
    await user.click(existing[0]!);
    await waitFor(() => expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("7"));
    return user;
  }

  it("cannot be edited until the workflow is saved, and saving first lets it be", async () => {
    renderWorkflow("?version=2&agentId=agent1&agentVer=3");
    const user = await addExistingLlm();

    // It exists only in unsaved state, so the resource editor could not cascade
    // into it: there is no link, only a way to save first.
    const unsaved = screen.getAllByTestId(/^unsaved-step-/);
    expect(unsaved).toHaveLength(1);
    const index = unsaved[0]!.getAttribute("data-testid")!.replace("unsaved-step-", "");
    expect(screen.getByTestId(`pipeline-item-${index}`).querySelector("a[href]")).toBeNull();

    await user.click(screen.getByTestId(`save-and-edit-${index}`));

    // The workflow was saved, then the resource opened against the NEW workflow version.
    await waitFor(() => expect(screen.getByTestId("location")).toHaveTextContent("/manage/resources/llm/"));
    const url = new URL(screen.getByTestId("location").textContent!, "http://x");
    expect(url.searchParams.get("wfId")).toBe("wf1");
    expect(url.searchParams.get("wfVer")).toBe("3");
    expect(url.searchParams.get("agentVer")).toBe("4");
  });

  it("shows no delete-created option when the discarded edit created nothing", async () => {
    renderWorkflow();
    await waitFor(() => expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6"));
    // A removed step is dirty enough to discard; the created-resource checkbox
    // only shows when something was created, which "Create new" records.
    await userEvent.setup().click(screen.getAllByTestId(/^remove-ext-/)[0]!);
    await userEvent.setup().click(screen.getByTestId("discard-btn"));
    await screen.findByTestId("unsaved-confirm");
    expect(screen.queryByTestId("discard-delete-created")).not.toBeInTheDocument();
  });
});

describe("WorkflowDetailPage — discarding", () => {
  it("asks before discarding, and keeps the edits on Cancel", async () => {
    renderWorkflow();
    await removeFirstStep();
    const user = userEvent.setup();
    await user.click(screen.getByTestId("discard-btn"));
    await user.click(await screen.findByTestId("unsaved-cancel"));
    expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("5");

    await user.click(screen.getByTestId("discard-btn"));
    await user.click(await screen.findByTestId("unsaved-confirm"));
    await waitFor(() => expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument());
    expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6");
  });

  it("saves with Ctrl+S", async () => {
    let puts = 0;
    server.use(
      http.put("*/workflowstore/workflows/:id", () => {
        puts++;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=3" },
        });
      }),
    );
    renderWorkflow();
    await removeFirstStep();
    const user = userEvent.setup();
    await user.keyboard("{Control>}s{/Control}");
    await waitFor(() => expect(puts).toBe(1));
  });
});

describe("WorkflowDetailPage — viewers", () => {
  it("shows the pipeline without any editing controls to a viewer", async () => {
    server.use(
      http.get("*/descriptorstore/descriptors/:id", ({ request }) => {
        const version = Number(new URL(request.url).searchParams.get("version") ?? 1);
        return HttpResponse.json({
          resource: `eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=${version}`,
          name: "Shared workflow",
          description: "",
          createdOn: 1,
          lastModifiedOn: 2,
          callerLevel: "VIEW",
        });
      }),
    );
    renderWorkflow("?agentId=agent1&agentVer=3");
    await waitFor(() => expect(screen.getByText("Shared workflow")).toBeInTheDocument());
    await waitFor(() => expect(screen.getByTestId("pipeline-step-count")).toHaveTextContent("6"));
    expect(screen.queryByTestId("save-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("discard-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("save-test-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("add-extension-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("workflow-title-edit")).not.toBeInTheDocument();
  });
});

describe("WorkflowDetailPage — renaming", () => {
  it("renames the workflow in place", async () => {
    const patches: unknown[] = [];
    server.use(
      http.patch("*/descriptorstore/descriptors/:id", async ({ request }) => {
        patches.push(await request.json());
        return new HttpResponse(null, { status: 200 });
      }),
    );
    renderWorkflow();
    const user = userEvent.setup();
    await user.click(await screen.findByTestId("workflow-title-edit"));
    const name = screen.getByTestId("workflow-title-name");
    await user.clear(name);
    await user.type(name, "Renamed pipeline");
    await user.click(screen.getByTestId("workflow-title-save"));
    await waitFor(() => expect(patches).toHaveLength(1));
    expect(patches[0]).toMatchObject({ document: { name: "Renamed pipeline" } });
  });
});
