import { afterEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { AgentDetailPage } from "@/pages/agent-detail";

/**
 * The agent detail page only shows, edits or deploys a version once it knows
 * which version is current, and offers a viewer nothing that writes.
 *
 * agent1's latest version is v3 throughout. The default MSW backend answers
 * `currentversion` with 1 for every resource, which is also the number the page
 * used to fall back to while the version list loaded, so a page that showed v1
 * in that window looked correct against it.
 */
const LATEST = 3;

/** agent1 at `version`: spend limit and HITL set, so both sections open by default. */
function agentDoc(version: number) {
  return {
    workflows: ["eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2"],
    channels: [],
    description: `Version ${version}`,
    maxCostPerConversationUsd: version,
    hitlConfig: { approvalTimeout: "PT15M", timeoutPolicy: "REJECT" },
  };
}

function descriptor(version: number, callerLevel?: string) {
  return {
    resource: `eddi://ai.labs.agent/agentstore/agents/agent1?version=${version}`,
    name: "Support Agent",
    description: "",
    createdOn: 1,
    lastModifiedOn: 2,
    ...(callerLevel ? { callerLevel } : {}),
  };
}

const versionOf = (request: Request) => Number(new URL(request.url).searchParams.get("version"));

/**
 * agent1 at v3, recording the `version` of every agent document read and the
 * number of writes. Requests for other agents fall through to the defaults.
 */
function agentAtLatest() {
  const requested: string[] = [];
  const writes = { count: 0 };
  let currentVersionReads = 0;
  server.use(
    http.get("*/agentstore/agents/:id/currentversion", ({ params }) => {
      if (params.id !== "agent1") return undefined;
      currentVersionReads++;
      return HttpResponse.json(LATEST);
    }),
    http.get("*/agentstore/agents/:id", ({ params, request }) => {
      if (params.id !== "agent1") return undefined;
      const version = new URL(request.url).searchParams.get("version");
      requested.push(version ?? "none");
      return HttpResponse.json(agentDoc(Number(version)));
    }),
    http.put("*/agentstore/agents/:id", () => {
      writes.count++;
      return new HttpResponse(null, {
        status: 200,
        headers: { Location: `eddi://ai.labs.agent/agentstore/agents/agent1?version=${LATEST + 1}` },
      });
    }),
  );
  return { requested, writes, currentVersionReads: () => currentVersionReads };
}

const renderDetail = () => renderPage("/manage/agentview/agent1", <AgentDetailPage />, "/manage/agentview/:id");

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AgentDetailPage — load order", () => {
  it("asks for no version and offers no deploy until the current version is known", async () => {
    const backend = agentAtLatest();
    let release!: () => void;
    const versionsHeld = new Promise<void>((resolve) => {
      release = resolve;
    });
    server.use(
      http.get("*/descriptorstore/descriptors/:id", async ({ params, request }) => {
        if (params.id !== "agent1") return undefined;
        await versionsHeld;
        return HttpResponse.json(descriptor(versionOf(request)));
      }),
    );

    renderDetail();
    await waitFor(() => expect(backend.currentVersionReads()).toBeGreaterThan(0));
    // Long enough for a document read issued alongside the version list to land.
    await new Promise((r) => setTimeout(r, 100));
    expect(backend.requested).toEqual([]);
    expect(screen.getByTestId("agent-detail-loading")).toBeInTheDocument();
    expect(screen.queryByTestId("deploy-btn")).not.toBeInTheDocument();

    release();
    expect(await screen.findByTestId("version-picker")).toHaveValue(String(LATEST));
    await screen.findByTestId("deploy-btn");
    expect([...new Set(backend.requested)]).toEqual([String(LATEST)]);
  });

  it("shows an error, not version 1, when the version list cannot be read", async () => {
    const backend = agentAtLatest();
    let failing = true;
    server.use(
      http.get("*/descriptorstore/descriptors/:id", ({ params, request }) => {
        if (params.id !== "agent1") return undefined;
        if (failing) return new HttpResponse(null, { status: 500 });
        return HttpResponse.json(descriptor(versionOf(request)));
      }),
    );

    renderDetail();
    const panel = await screen.findByTestId("agent-detail-error");
    expect(screen.queryByTestId("deploy-btn")).not.toBeInTheDocument();
    expect(backend.requested).toEqual([]);

    // Retry reads the version list again, not only the document.
    failing = false;
    await userEvent.click(within(panel).getByRole("button"));
    expect(await screen.findByTestId("version-picker")).toHaveValue(String(LATEST));
    expect([...new Set(backend.requested)]).toEqual([String(LATEST)]);
  });
});

describe("AgentDetailPage — switching versions", () => {
  it("keeps one Limits and one Human-in-the-Loop section", async () => {
    agentAtLatest();
    const consoleError = vi.spyOn(console, "error");

    renderDetail();
    const picker = await screen.findByTestId("version-picker");
    await waitFor(() => expect(screen.getByTestId("max-cost-per-conversation")).toHaveValue(LATEST));

    await userEvent.selectOptions(picker, "2");
    await waitFor(() => expect(screen.getByTestId("max-cost-per-conversation")).toHaveValue(2));
    await waitFor(() => expect(screen.getByTestId("agent-config-sections")).toHaveAttribute("aria-busy", "false"));

    expect(screen.getAllByTestId("limits-section")).toHaveLength(1);
    expect(screen.getAllByTestId("hitl-config-section")).toHaveLength(1);
    const duplicateKeyWarnings = consoleError.mock.calls.filter((args) =>
      args.some((a) => typeof a === "string" && a.includes("same key")),
    );
    expect(duplicateKeyWarnings).toEqual([]);
  });
});

describe("AgentDetailPage — viewers", () => {
  function sharedForViewing() {
    const backend = agentAtLatest();
    server.use(
      http.get("*/descriptorstore/descriptors/:id", ({ params, request }) => {
        if (params.id !== "agent1") return undefined;
        return HttpResponse.json(descriptor(versionOf(request), "VIEW"));
      }),
    );
    return backend;
  }

  it("shows the configuration without anything that edits or deploys it", async () => {
    sharedForViewing();
    renderDetail();
    await screen.findByTestId("version-picker");
    await screen.findByTestId("env-badges");

    // What a viewer may still do: read the status, duplicate, export, chat.
    expect(screen.getByTestId("deployment-status")).toBeInTheDocument();
    expect(screen.getByTestId("duplicate-agent-btn")).toBeInTheDocument();
    expect(screen.getByTestId("export-agent-btn")).toBeInTheDocument();
    expect(screen.getByTestId("chat-btn")).toBeInTheDocument();

    expect(screen.queryByTestId("deploy-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("deploy-chat-btn")).not.toBeInTheDocument();
    expect(screen.queryAllByTestId(/^env-toggle-/)).toEqual([]);
    expect(screen.queryByTestId("agent-save-controls")).not.toBeInTheDocument();
    expect(screen.queryByTestId("add-workflow-btn")).not.toBeInTheDocument();
    expect(screen.queryByTestId("remove-workflow-wf1")).not.toBeInTheDocument();
    expect(screen.getByTestId("max-cost-per-conversation")).toBeDisabled();
  });

  it("can still open a section to read it, and Ctrl+S writes nothing", async () => {
    const backend = sharedForViewing();
    renderDetail();
    await screen.findByTestId("version-picker");

    // The sections are read-only one by one, not through a disabled fieldset,
    // which would also disable the headers that expand them.
    expect(screen.getByRole("button", { name: /Capabilities/i })).toBeEnabled();

    await userEvent.type(screen.getByTestId("max-cost-per-conversation"), "9");
    await userEvent.keyboard("{Control>}s{/Control}");
    await new Promise((r) => setTimeout(r, 50));
    expect(screen.queryByTestId("unsaved-bar")).not.toBeInTheDocument();
    expect(backend.writes.count).toBe(0);
  });
});
