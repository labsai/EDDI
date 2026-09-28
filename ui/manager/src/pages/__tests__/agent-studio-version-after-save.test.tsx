/**
 * Agent Studio — saving one stage and then another.
 *
 * Every save bumps the resource, the workflow and the agent, and the backend
 * refuses a write to a version that is no longer current. The studio's panel
 * used to keep the workflow and agent versions in its own state, and each stage
 * is a separate panel mount: the second stage's panel started from the page's
 * stale versions (a descriptor cached for a minute, a workflow query keyed by
 * id alone), so its save wrote the resource and then 409'd on the workflow,
 * leaving an orphaned resource version. Reopening the first stage edited its
 * pre-save version for the same reason.
 *
 * The backend here is a small stateful fake that answers exactly like the real
 * one on the part that matters: a PUT to a non-current version is a 409.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { AgentStudioPage } from "@/pages/agent-studio";

// Counts editor mounts: a remount is what discards anything typed meanwhile.
const editorMounts = vi.hoisted(() => ({ count: 0 }));

// The editor chrome is not under test — a button that saves what was loaded.
vi.mock("@/components/editors/config-editor-layout", async () => {
  const { useEffect } = await vi.importActual<typeof import("react")>("react");
  return {
    ConfigEditorLayout: (props: {
      resourceId: string;
      currentVersion: number;
      data: string;
      onSave: (json: string) => void;
    }) => {
      useEffect(() => {
        editorMounts.count += 1;
      }, []);
      return (
        <button
          type="button"
          data-testid={`mock-save-${props.resourceId}`}
          data-version={props.currentVersion}
          onClick={() => props.onSave(props.data)}
        >
          save
        </button>
      );
    },
  };
});

// Keep the right-hand chat out of the way; it has nothing to do with saving.
vi.mock("@/components/chat/chat-panel", () => ({ ChatPanel: () => null }));

interface Versioned<T> {
  current: number;
  docs: Record<number, T>;
}

function versioned<T>(doc: T): Versioned<T> {
  return { current: 1, docs: { 1: doc } };
}

const RULES = "eddi://ai.labs.rules/rulestore/rulesets/beh1";
const LLM = "eddi://ai.labs.llm/llmstore/llms/llm1";
const WF = "eddi://ai.labs.workflow/workflowstore/workflows/wf1";

let agent: Versioned<{ workflows: string[] }>;
let workflow: Versioned<{ workflowSteps: { type: string; extensions: object; config: { uri: string } }[] }>;
let resources: Record<string, Versioned<Record<string, unknown>>>;
let conflicts: string[];
/** Hops to fail once with a 500, to simulate a cascade that stops partway. */
let failOnce: Set<string>;

function installBackend() {
  agent = versioned({ workflows: [`${WF}?version=1`] });
  workflow = versioned({
    workflowSteps: [
      { type: "eddi://ai.labs.rules", extensions: {}, config: { uri: `${RULES}?version=1` } },
      { type: "eddi://ai.labs.llm", extensions: {}, config: { uri: `${LLM}?version=1` } },
    ],
  });
  resources = { beh1: versioned({ behaviorGroups: [] }), llm1: versioned({ tasks: [] }) };
  conflicts = [];
  failOnce = new Set();

  function read<T>(store: Versioned<T>, request: Request) {
    const v = Number(new URL(request.url).searchParams.get("version"));
    const doc = store.docs[v];
    return doc ? HttpResponse.json(doc) : new HttpResponse(null, { status: 404 });
  }

  async function write<T>(store: Versioned<T>, request: Request, location: string, what: string) {
    const v = Number(new URL(request.url).searchParams.get("version"));
    if (failOnce.delete(what)) {
      return HttpResponse.json({ message: `${what} unavailable` }, { status: 500 });
    }
    if (v !== store.current) {
      conflicts.push(`${what}@${v}`);
      return HttpResponse.json({ message: "not the current version" }, { status: 409 });
    }
    store.current += 1;
    store.docs[store.current] = (await request.json()) as T;
    return new HttpResponse(null, {
      status: 200,
      headers: { Location: `${location}?version=${store.current}` },
    });
  }

  server.use(
    http.get("*/agentstore/agents/descriptors", () =>
      HttpResponse.json([
        {
          resource: `eddi://ai.labs.agent/agentstore/agents/agent1?version=${agent.current}`,
          name: "Studio Agent",
          lastModifiedOn: 0,
        },
      ]),
    ),
    http.get("*/agentstore/agents/agent1", ({ request }) => read(agent, request)),
    http.put("*/agentstore/agents/agent1", ({ request }) =>
      write(agent, request, "eddi://ai.labs.agent/agentstore/agents/agent1", "agent"),
    ),
    http.get("*/workflowstore/workflows/wf1", ({ request }) => read(workflow, request)),
    http.put("*/workflowstore/workflows/wf1", ({ request }) => write(workflow, request, WF, "workflow")),
    http.get("*/rulestore/rulesets/beh1", ({ request }) => read(resources.beh1!, request)),
    http.put("*/rulestore/rulesets/beh1", ({ request }) => write(resources.beh1!, request, RULES, "beh1")),
    http.get("*/llmstore/llms/llm1", ({ request }) => read(resources.llm1!, request)),
    http.put("*/llmstore/llms/llm1", ({ request }) => write(resources.llm1!, request, LLM, "llm1")),
  );
}

async function saveStage(user: ReturnType<typeof userEvent.setup>, stage: number, resourceId: string) {
  await waitFor(() => expect(screen.getAllByTestId(`stage-${stage}`).length).toBeGreaterThan(0));
  await user.click(screen.getAllByTestId(`stage-${stage}`)[0]!);
  const button = await screen.findAllByTestId(`mock-save-${resourceId}`, {}, { timeout: 3000 });
  await user.click(button[0]!);
}

describe("Agent Studio — saving more than one stage", () => {
  beforeEach(() => {
    installBackend();
    editorMounts.count = 0;
  });

  it("saves a second stage on top of the first save instead of 409ing on the workflow", async () => {
    renderPage("/manage/studio/agent1", <AgentStudioPage />, "/manage/studio/:agentId");
    const user = userEvent.setup();

    await saveStage(user, 0, "beh1");
    await waitFor(() => expect(agent.current).toBe(2));

    await saveStage(user, 1, "llm1");
    await waitFor(() => expect(agent.current).toBe(3));

    expect(conflicts).toEqual([]);
    expect(resources.llm1!.current).toBe(2);
    // The second save built on the first: the final workflow carries BOTH edits.
    expect(workflow.docs[workflow.current]!.workflowSteps.map((s) => s.config.uri)).toEqual([
      `${RULES}?version=2`,
      `${LLM}?version=2`,
    ]);
    expect(agent.docs[agent.current]!.workflows).toEqual([`${WF}?version=3`]);
  });

  it("reopening a saved stage edits the version the save created", async () => {
    renderPage("/manage/studio/agent1", <AgentStudioPage />, "/manage/studio/:agentId");
    const user = userEvent.setup();

    await saveStage(user, 0, "beh1");
    await waitFor(() => expect(agent.current).toBe(2));
    await saveStage(user, 1, "llm1");
    await waitFor(() => expect(agent.current).toBe(3));

    // Back to the first stage: its panel must open beh1 v2, not the v1 that the
    // pre-save pipeline still pointed at.
    await user.click(screen.getAllByTestId("stage-0")[0]!);
    await waitFor(() =>
      expect(screen.getAllByTestId("mock-save-beh1")[0]!.getAttribute("data-version")).toBe("2"),
    );
    await user.click(screen.getAllByTestId("mock-save-beh1")[0]!);
    await waitFor(() => expect(agent.current).toBe(4));

    expect(conflicts).toEqual([]);
    expect(resources.beh1!.current).toBe(3);
  });

  it.each([
    ["the workflow hop", "workflow"],
    ["the agent hop", "agent"],
  ])("a retry after %s failed completes instead of 409ing on what the first try wrote", async (_label, hop) => {
    failOnce.add(hop);
    renderPage("/manage/studio/agent1", <AgentStudioPage />, "/manage/studio/:agentId");
    const user = userEvent.setup();

    await saveStage(user, 0, "beh1");
    // The first try wrote the resource (and, for the agent case, the workflow).
    await waitFor(() => expect(resources.beh1!.current).toBe(2));
    expect(agent.current).toBe(1);

    await user.click(screen.getAllByTestId("mock-save-beh1")[0]!);
    await waitFor(() => expect(agent.current).toBe(2));

    expect(conflicts).toEqual([]);
    const finalWorkflow = workflow.docs[workflow.current]!;
    expect(finalWorkflow.workflowSteps[0]!.config.uri).toBe(`${RULES}?version=3`);
    expect(agent.docs[2]!.workflows).toEqual([`${WF}?version=${workflow.current}`]);
  });

  it("does not remount the editor when a save moves the stage to a new version", async () => {
    renderPage("/manage/studio/agent1", <AgentStudioPage />, "/manage/studio/:agentId");
    const user = userEvent.setup();

    await saveStage(user, 0, "beh1");
    await waitFor(() => expect(agent.current).toBe(2));
    // Wait for the refetched pipeline to carry the new step URI.
    await waitFor(() =>
      expect(screen.getAllByTestId("mock-save-beh1")[0]!.getAttribute("data-version")).toBe("2"),
    );
    await new Promise((r) => setTimeout(r, 100));

    // The desktop and the mobile panel each mounted once, and only once.
    expect(editorMounts.count).toBeLessThanOrEqual(2);
  });

  it("returning to a stage after a workflow-hop failure retries from the version it wrote", async () => {
    failOnce.add("workflow");
    renderPage("/manage/studio/agent1", <AgentStudioPage />, "/manage/studio/:agentId");
    const user = userEvent.setup();

    await saveStage(user, 0, "beh1");
    await waitFor(() => expect(resources.beh1!.current).toBe(2));
    expect(agent.current).toBe(1);

    // Away and back: the pipeline still says beh1 v1, the panel remounts.
    await user.click(screen.getAllByTestId("stage-1")[0]!);
    await screen.findAllByTestId("mock-save-llm1");
    await user.click(screen.getAllByTestId("stage-0")[0]!);
    await waitFor(() =>
      expect(screen.getAllByTestId("mock-save-beh1")[0]!.getAttribute("data-version")).toBe("2"),
    );

    await user.click(screen.getAllByTestId("mock-save-beh1")[0]!);
    await waitFor(() => expect(agent.current).toBe(2));
    expect(conflicts).toEqual([]);
  });
});
