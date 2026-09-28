import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { renderPage } from "@/test/test-utils";
import { AgentDetailPage } from "@/pages/agent-detail";
import { WorkflowDetailPage } from "@/pages/workflow-detail";
import { ResourceDetailPage } from "@/pages/resource-detail";
import { server } from "@/test/mocks/server";
import { http, HttpResponse } from "msw";

/**
 * Delete needs OWN on the detail pages, exactly as on the list pages.
 *
 * Found by signing in as an eddi-editor holding an EDIT grant with workspaces
 * enforced: the agents list hid Delete, but the agent's own page offered it and
 * the click ended in a 403. Each page is rendered twice against the same
 * fixture, differing only in the `callerLevel` the backend stamps — so a pass
 * here is the level deciding, not some other difference between the renders.
 *
 * Every case first waits for a control that renders for both levels, so the
 * "absent" assertions cannot pass merely because the page had not loaded yet.
 */

function descriptor(resource: string, name: string, callerLevel?: string) {
  return {
    resource,
    name,
    description: "",
    createdOn: Date.now() - 86400000,
    lastModifiedOn: Date.now() - 3600000,
    ...(callerLevel ? { callerLevel } : {}),
  };
}

describe("Agent detail — owner-only actions", () => {
  function withLevel(callerLevel?: string) {
    server.use(
      http.get("*/agentstore/agents/descriptors", () =>
        HttpResponse.json([
          descriptor("eddi://ai.labs.agent/agentstore/agents/agent1?version=1", "Support Agent", callerLevel),
        ]),
      ),
      http.get("*/agentstore/agents/agent1/currentversion", () => HttpResponse.json(1)),
    );
    renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");
  }

  it("hides Delete from an EDIT grantee", async () => {
    withLevel("EDIT");
    await screen.findByTestId("export-agent-btn");
    // Give the descriptor query a chance to settle before asserting absence.
    await waitFor(() => expect(screen.getByText("Support Agent")).toBeInTheDocument());
    expect(screen.queryByTestId("delete-agent-btn")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Delete agent" })).not.toBeInTheDocument();
  });

  it("offers Delete to the owner", async () => {
    withLevel("OWN");
    expect(await screen.findByRole("button", { name: "Delete agent" })).toBeEnabled();
  });

  it("offers Delete when the backend reports no level (workspaces off)", async () => {
    withLevel(undefined);
    expect(await screen.findByTestId("delete-agent-btn")).toBeInTheDocument();
  });

  it("hides Delete when enforcement is on and no descriptor for this agent came back", async () => {
    // An empty lookup is not "no level". Reading it as unrestricted showed a
    // non-owner Delete whenever the descriptor query came back short.
    server.use(
      http.get("*/workspaces", () =>
        HttpResponse.json({ enabled: true, principal: "editor", spaces: [], seesEverything: false }),
      ),
      http.get("*/agentstore/agents/descriptors", () => HttpResponse.json([])),
      http.get("*/agentstore/agents/agent1/currentversion", () => HttpResponse.json(1)),
    );
    renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");
    await screen.findByTestId("export-agent-btn");
    // Let both /workspaces and the descriptors settle before asserting absence.
    await new Promise((r) => setTimeout(r, 300));
    expect(screen.queryByTestId("delete-agent-btn")).not.toBeInTheDocument();
  });

  it("keeps Delete when workspaces are off and no descriptor came back", async () => {
    server.use(
      http.get("*/workspaces", () =>
        HttpResponse.json({ enabled: false, spaces: [], seesEverything: true }),
      ),
      http.get("*/agentstore/agents/descriptors", () => HttpResponse.json([])),
      http.get("*/agentstore/agents/agent1/currentversion", () => HttpResponse.json(1)),
    );
    renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");
    expect(await screen.findByTestId("delete-agent-btn")).toBeInTheDocument();
  });
});

describe("Workflow detail — owner-only actions", () => {
  function withLevel(callerLevel: string) {
    server.use(
      http.get("*/workflowstore/workflows/descriptors", () =>
        HttpResponse.json([
          descriptor("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1", "Support Ticket Pipeline", callerLevel),
        ]),
      ),
      http.get("*/workflowstore/workflows/wf1/currentversion", () => HttpResponse.json(1)),
    );
    renderPage("/manage/workflowview/wf1", <WorkflowDetailPage />, "/manage/workflowview/:id");
  }

  it("hides Delete from an EDIT grantee", async () => {
    withLevel("EDIT");
    await screen.findByTestId("pipeline-step-count");
    await waitFor(() => expect(screen.getByText("Support Ticket Pipeline")).toBeInTheDocument());
    expect(screen.queryByTestId("delete-wf-btn")).not.toBeInTheDocument();
  });

  it("offers Delete to the owner", async () => {
    withLevel("OWN");
    expect(await screen.findByTestId("delete-wf-btn")).toBeInTheDocument();
  });
});

describe("Resource detail — owner-only actions", () => {
  function withLevel(callerLevel: string) {
    server.use(
      http.get("*/llmstore/llms/descriptors", () =>
        HttpResponse.json([
          descriptor("eddi://ai.labs.llm/llmstore/llms/res1?version=1", "My LLM", callerLevel),
        ]),
      ),
      http.get("*/llmstore/llms/res1/currentversion", () => HttpResponse.json(1)),
    );
    renderPage("/manage/resources/llm/res1", <ResourceDetailPage />, "/manage/resources/:type/:id");
  }

  it("hides Delete from an EDIT grantee", async () => {
    withLevel("EDIT");
    await waitFor(() => expect(screen.getByText("My LLM")).toBeInTheDocument());
    expect(screen.getByRole("button", { name: /duplicate/i })).toBeInTheDocument();
    expect(screen.queryByTestId("delete-resource-btn")).not.toBeInTheDocument();
  });

  it("offers Delete to the owner", async () => {
    withLevel("OWN");
    expect(await screen.findByTestId("delete-resource-btn")).toBeInTheDocument();
  });
});
