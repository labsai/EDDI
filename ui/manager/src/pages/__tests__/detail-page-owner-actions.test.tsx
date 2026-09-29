import { describe, expect, it } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import type { QueryClient } from "@tanstack/react-query";
import { renderPage } from "@/test/test-utils";
import { agentKeys } from "@/lib/query-keys";
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

/**
 * Resolves once both inputs to the agent page's Delete decision have settled —
 * the version descriptors and `/workspaces` — whichever way they went. A
 * negative assertion made before then passes merely because access is still
 * pending, which is the one state guaranteed to hide Delete.
 */
async function accessSettled(client: QueryClient, agentId: string) {
  await waitFor(() => {
    for (const key of [[...agentKeys.all, "versions", agentId], ["workspaces", "info"]]) {
      const state = client.getQueryState(key)?.status;
      expect(state === "success" || state === "error").toBe(true);
    }
  });
}

/**
 * Answers the per-id descriptor read (`GET /descriptorstore/descriptors/:id`),
 * which is how every detail page loads its version list — see
 * `getDescriptorVersions`. The store listing (`…/descriptors?filter=`) is only
 * the fallback when that read finds nothing.
 */
function descriptorRead(resource: (version: number) => string, name: string, callerLevel?: string) {
  return http.get("*/descriptorstore/descriptors/:id", ({ request }) => {
    const version = Number(new URL(request.url).searchParams.get("version") ?? 1);
    return HttpResponse.json(descriptor(resource(version), name, callerLevel));
  });
}

/** No descriptor for any version, by id or in the fallback listing. */
function noDescriptors(listing: string) {
  return [
    http.get("*/descriptorstore/descriptors/:id", () => HttpResponse.json({}, { status: 404 })),
    http.get(listing, () => HttpResponse.json([])),
  ];
}

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
      descriptorRead((v) => `eddi://ai.labs.agent/agentstore/agents/agent1?version=${v}`, "Support Agent", callerLevel),
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
      ...noDescriptors("*/agentstore/agents/descriptors"),
      http.get("*/agentstore/agents/agent1/currentversion", () => HttpResponse.json(1)),
    );
    const { queryClient } = renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");
    await screen.findByTestId("export-agent-btn");
    await accessSettled(queryClient, "agent1");
    expect(screen.queryByTestId("delete-agent-btn")).not.toBeInTheDocument();
  });

  it("hides Delete when /workspaces fails and no descriptor came back", async () => {
    // useSpaces folds a failure into `enabled: false`. Read as "enforcement
    // off", a 502 here offered Delete to anyone whose lookup came back empty.
    server.use(
      http.get("*/workspaces", () => HttpResponse.json({}, { status: 502 })),
      ...noDescriptors("*/agentstore/agents/descriptors"),
      http.get("*/agentstore/agents/agent1/currentversion", () => HttpResponse.json(1)),
    );
    const { queryClient } = renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");
    await screen.findByTestId("export-agent-btn");
    await accessSettled(queryClient, "agent1");
    expect(queryClient.getQueryState(["workspaces", "info"])?.status).toBe("error");
    expect(screen.queryByTestId("delete-agent-btn")).not.toBeInTheDocument();
  });

  it("keeps Delete when an older backend 404s /workspaces and no descriptor came back", async () => {
    server.use(
      http.get("*/workspaces", () => HttpResponse.json({}, { status: 404 })),
      ...noDescriptors("*/agentstore/agents/descriptors"),
      http.get("*/agentstore/agents/agent1/currentversion", () => HttpResponse.json(1)),
    );
    renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");
    expect(await screen.findByTestId("delete-agent-btn")).toBeInTheDocument();
  });

  it("keeps Delete when workspaces are off and no descriptor came back", async () => {
    server.use(
      http.get("*/workspaces", () =>
        HttpResponse.json({ enabled: false, spaces: [], seesEverything: true }),
      ),
      ...noDescriptors("*/agentstore/agents/descriptors"),
      http.get("*/agentstore/agents/agent1/currentversion", () => HttpResponse.json(1)),
    );
    renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");
    expect(await screen.findByTestId("delete-agent-btn")).toBeInTheDocument();
  });
});

describe("Workflow detail — owner-only actions", () => {
  function withLevel(callerLevel: string) {
    server.use(
      descriptorRead(
        (v) => `eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=${v}`,
        "Support Ticket Pipeline",
        callerLevel,
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
      descriptorRead((v) => `eddi://ai.labs.llm/llmstore/llms/res1?version=${v}`, "My LLM", callerLevel),
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
