import { describe, it, expect, afterEach } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { Toaster, toast } from "sonner";
import userEvent from "@testing-library/user-event";
import { ThemeProvider } from "@/components/layout/theme-provider";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/**
 * A config-editor Save that fails partway must be retryable.
 *
 * The Save cascades resource → workflow → agent, and every hop bumps a version.
 * When a later hop failed, the page kept the versions it was opened with — but
 * the resource (and perhaps the workflow) had already moved on, and the backend
 * refuses a write to a version that is no longer current. Every retry 409'd on
 * the resource until the page was reloaded, which threw the edit away.
 */

function renderEditor() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <MemoryRouter
      initialEntries={["/manage/resources/llm/res1?wfId=wf1&wfVer=1&agentId=agent1&agentVer=1"]}
    >
      <QueryClientProvider client={queryClient}>
        <ThemeProvider defaultTheme="light" storageKey="eddi-theme-test">
          <Routes>
            <Route path="/manage/resources/:type/:id" element={<ResourceDetailPage />} />
          </Routes>
          <Toaster duration={600_000} />
        </ThemeProvider>
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

interface Chain {
  resource: number;
  workflow: number;
  agent: number;
  /** The workflow version each agent version references. */
  agentWorkflowRef: Record<number, number>;
  conflicts: string[];
  resourcePuts: number[];
}

/** A stateful fake of the three stores: a PUT to a non-current version is a 409. */
function stubChain(failOnce: string): Chain {
  const chain: Chain = {
    resource: 1,
    workflow: 1,
    agent: 1,
    agentWorkflowRef: { 1: 1 },
    conflicts: [],
    resourcePuts: [],
  };
  let failed = false;
  const version = (request: Request) => Number(new URL(request.url).searchParams.get("version"));
  const failFirst = (what: string) => {
    if (what === failOnce && !failed) {
      failed = true;
      return HttpResponse.json({ message: `${what} unavailable` }, { status: 500 });
    }
    return null;
  };

  server.use(
    http.put("*/llmstore/llms/res1", ({ request }) => {
      chain.resourcePuts.push(version(request));
      if (version(request) !== chain.resource) {
        chain.conflicts.push(`resource@${version(request)}`);
        return new HttpResponse(null, { status: 409 });
      }
      chain.resource += 1;
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `eddi://ai.labs.llm/llmstore/llms/res1?version=${chain.resource}` },
      });
    }),
    http.get("*/workflowstore/workflows/wf1", () =>
      HttpResponse.json({
        workflowSteps: [
          { type: "eddi://ai.labs.llm", config: { uri: "eddi://ai.labs.llm/llmstore/llms/res1?version=1" } },
        ],
      }),
    ),
    http.put("*/workflowstore/workflows/wf1", ({ request }) => {
      const failure = failFirst("workflow");
      if (failure) return failure;
      if (version(request) !== chain.workflow) {
        chain.conflicts.push(`workflow@${version(request)}`);
        return new HttpResponse(null, { status: 409 });
      }
      chain.workflow += 1;
      return new HttpResponse(null, {
        status: 200,
        headers: {
          Location: `eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=${chain.workflow}`,
        },
      });
    }),
    http.get("*/agentstore/agents/agent1", ({ request }) =>
      HttpResponse.json({
        workflows: [
          `eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=${chain.agentWorkflowRef[version(request)]}`,
        ],
      }),
    ),
    http.put("*/agentstore/agents/agent1", async ({ request }) => {
      const failure = failFirst("agent");
      if (failure) return failure;
      if (version(request) !== chain.agent) {
        chain.conflicts.push(`agent@${version(request)}`);
        return new HttpResponse(null, { status: 409 });
      }
      const body = (await request.json()) as { workflows: string[] };
      chain.agent += 1;
      chain.agentWorkflowRef[chain.agent] = Number(body.workflows[0]!.split("version=")[1]);
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `eddi://ai.labs.agent/agentstore/agents/agent1?version=${chain.agent}` },
      });
    }),
  );
  return chain;
}

afterEach(() => {
  toast.dismiss();
});

describe("ResourceDetailPage — retrying a Save that failed partway", () => {
  it.each([["workflow"], ["agent"]])(
    "a retry after the %s hop failed completes without a 409",
    async (hop) => {
      const chain = stubChain(hop);
      renderEditor();
      const user = userEvent.setup();

      await waitFor(() => expect(screen.getByTestId("model-type-select")).toBeInTheDocument());
      await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");
      await waitFor(() => expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument());
      await user.click(screen.getByTestId("save-btn"));

      // The first try wrote the resource, then stopped.
      await waitFor(() => expect(chain.resource).toBe(2));
      await waitFor(() => expect(screen.getByText(new RegExp(`${hop} unavailable`))).toBeInTheDocument());
      expect(chain.agent).toBe(1);

      // Retry: the page moved onto the version it wrote. Whether the editor
      // still counts the edit as unsaved depends on the refetch, so edit again.
      await waitFor(() => expect(screen.getByTestId("model-type-select")).toBeInTheDocument());
      await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");
      await waitFor(() => expect(screen.getByTestId("save-btn")).toBeEnabled());
      await user.click(screen.getByTestId("save-btn"));

      await waitFor(() => expect(chain.agent).toBe(2));
      expect(chain.conflicts).toEqual([]);
      expect(chain.resourcePuts).toEqual([1, 2]);
      expect(chain.agentWorkflowRef[2]).toBe(chain.workflow);
    },
  );
});
