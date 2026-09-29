import { describe, it, expect } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { renderPage } from "@/test/test-utils";
import { server } from "@/test/mocks/server";

/**
 * A save in cascade mode writes a new AGENT version. Whether that version is
 * compatible with the one it replaces (running conversations may follow it)
 * is the user's call, made on this page — unticked, i.e. breaking, unless they
 * say otherwise — and it must reach the agent PUT as `compatible=true`.
 */

const PATH = "/manage/resources/llm/res1?wfId=wf1&wfVer=1&agentId=agent1&agentVer=1";
const ROUTE = "/manage/resources/:type/:id";

/** The cascade chain; returns the URLs of every agent PUT. */
function stubCascade(generation: number | null = 4): URL[] {
  const agentPuts: URL[] = [];
  server.use(
    http.put("*/llmstore/llms/:id", () =>
      new HttpResponse(null, {
        status: 200,
        headers: { Location: "eddi://ai.labs.llm/llmstore/llms/res1?version=2" },
      }),
    ),
    http.get("*/workflowstore/workflows/:id", () =>
      HttpResponse.json({
        workflowSteps: [{ config: { uri: "eddi://ai.labs.llm/llmstore/llms/res1?version=1" } }],
      }),
    ),
    http.put("*/workflowstore/workflows/:id", () =>
      new HttpResponse(null, {
        status: 200,
        headers: { Location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2" },
      }),
    ),
    http.get("*/agentstore/agents/:id", ({ request }) =>
      HttpResponse.json({
        compatibilityGeneration: generation,
        workflows: [
          `eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=${
            new URL(request.url).searchParams.get("version") === "1" ? 1 : 2
          }`,
        ],
      }),
    ),
    http.put("*/agentstore/agents/:id", ({ request }) => {
      agentPuts.push(new URL(request.url));
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=2" },
      });
    }),
  );
  return agentPuts;
}

async function editAndSave(user: ReturnType<typeof userEvent.setup>) {
  await waitFor(() => expect(screen.getByTestId("model-type-select")).toBeInTheDocument());
  await user.selectOptions(screen.getByTestId("model-type-select"), "azure-openai");
  await waitFor(() => expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument());
  await user.click(screen.getByTestId("save-btn"));
}

function checkbox(): HTMLInputElement {
  return screen.getByTestId("compatible-version-checkbox") as HTMLInputElement;
}

describe("ResourceDetailPage — compatible agent version on a cascade save", () => {
  it("offers the choice unticked, and a plain save stays breaking", async () => {
    const agentPuts = stubCascade();
    renderPage(PATH, <ResourceDetailPage />, ROUTE);
    const user = userEvent.setup();

    await screen.findByTestId("compatible-version-checkbox");
    expect(checkbox().checked).toBe(false);

    await editAndSave(user);
    await waitFor(() => expect(agentPuts).toHaveLength(1));
    expect(agentPuts[0]!.searchParams.has("compatible")).toBe(false);
  });

  it("sends compatible=true to the agent PUT when ticked, then resets", async () => {
    const agentPuts = stubCascade();
    renderPage(PATH, <ResourceDetailPage />, ROUTE);
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("compatible-version-checkbox"));
    await editAndSave(user);

    await waitFor(() => expect(agentPuts).toHaveLength(1));
    expect(agentPuts[0]!.searchParams.get("compatible")).toBe("true");
    // Each save is its own decision.
    await waitFor(() => expect(checkbox().checked).toBe(false));
  });

  it("warns, once ticked, when the current agent version predates version following", async () => {
    stubCascade(null);
    renderPage(PATH, <ResourceDetailPage />, ROUTE);
    const user = userEvent.setup();

    await user.click(await screen.findByTestId("compatible-version-checkbox"));
    expect(await screen.findByTestId("compatible-version-checkbox-note")).toBeInTheDocument();
  });

  it("says nothing more when the current version is on a chain", async () => {
    stubCascade(4);
    renderPage(PATH, <ResourceDetailPage />, ROUTE);
    const user = userEvent.setup();

    await waitFor(() => expect(screen.getByTestId("model-type-select")).toBeInTheDocument());
    await user.click(checkbox());
    // Give the agent read time to land, so the absence is not "not loaded yet".
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByTestId("compatible-version-checkbox-note")).not.toBeInTheDocument();
  });
});
