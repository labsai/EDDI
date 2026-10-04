/**
 * Agent Studio — unsaved edits, Save & Test, the workflow switcher, and the
 * chat that sits beside the editor.
 *
 * Choosing another pipeline stage replaces the editor (its key changes), which
 * used to throw away whatever was typed without a word.
 */
import { beforeEach, describe, expect, it, vi } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { renderPage, userEvent } from "@/test/test-utils";
import { server } from "@/test/mocks/server";
import { AgentStudioPage } from "@/pages/agent-studio";
import { useChatStore } from "@/hooks/use-chat";

const toastSuccess = vi.fn();
const toastError = vi.fn();
vi.mock("sonner", () => ({
  toast: Object.assign(vi.fn(), {
    success: (...args: unknown[]) => toastSuccess(...args),
    error: (...args: unknown[]) => toastError(...args),
    warning: vi.fn(),
  }),
}));

function renderStudio() {
  return renderPage("/manage/studio/agent1", <AgentStudioPage />, "/manage/studio/:agentId");
}

/** The mock workflow: 0 parser, 1 parser, 2 rules, 3 property, 4 llm, 5 output. */
const RULES_STAGE = 2;
const LLM_STAGE = 4;

async function selectStage(user: ReturnType<typeof userEvent.setup>, index: number) {
  const buttons = await screen.findAllByTestId(`stage-${index}`);
  await user.click(buttons[0]!);
}

/** Opens the LLM stage and makes an edit, leaving the editor dirty. */
async function dirtyLlmEditor(user: ReturnType<typeof userEvent.setup>) {
  await selectStage(user, LLM_STAGE);
  const select = (await screen.findAllByTestId("model-type-select"))[0]!;
  await user.selectOptions(select, "azure-openai");
  await waitFor(() => expect(screen.getAllByTestId("dirty-indicator").length).toBeGreaterThan(0));
}

describe("Agent Studio — unsaved edits when switching stages", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("switches straight away when the editor is clean", async () => {
    renderStudio();
    const user = userEvent.setup();
    await selectStage(user, LLM_STAGE);
    await screen.findAllByTestId("model-type-select");
    await selectStage(user, RULES_STAGE);
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });

  it("asks before replacing an edited editor, and Cancel keeps it", async () => {
    renderStudio();
    const user = userEvent.setup();
    await dirtyLlmEditor(user);

    await selectStage(user, RULES_STAGE);
    await user.click(await screen.findByTestId("unsaved-cancel"));

    // Still the LLM editor, still carrying the edit.
    expect(screen.getAllByTestId("model-type-select")[0]).toHaveValue("azure-openai");
    expect(screen.getAllByTestId("dirty-indicator").length).toBeGreaterThan(0);
  });

  it("discards the edit and switches on 'Discard & switch'", async () => {
    renderStudio();
    const user = userEvent.setup();
    await dirtyLlmEditor(user);

    await selectStage(user, RULES_STAGE);
    await user.click(await screen.findByTestId("unsaved-confirm"));

    await waitFor(() => expect(screen.queryByTestId("model-type-select")).not.toBeInTheDocument());
    expect(screen.queryByTestId("dirty-indicator")).not.toBeInTheDocument();
  });

  it("saves the edit first on 'Save', then switches", async () => {
    let llmPuts = 0;
    server.use(
      http.put("*/llmstore/llms/:id", () => {
        llmPuts++;
        return new HttpResponse(null, {
          status: 200,
          headers: { Location: "eddi://ai.labs.llm/llmstore/llms/llm1?version=2" },
        });
      }),
      http.put("*/workflowstore/workflows/:id", () =>
        new HttpResponse(null, {
          status: 200,
          headers: { Location: "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=3" },
        }),
      ),
      http.put("*/agentstore/agents/:id", () =>
        new HttpResponse(null, {
          status: 200,
          headers: { Location: "eddi://ai.labs.agent/agentstore/agents/agent1?version=4" },
        }),
      ),
    );
    renderStudio();
    const user = userEvent.setup();
    await dirtyLlmEditor(user);

    await selectStage(user, RULES_STAGE);
    await user.click(await screen.findByTestId("unsaved-save"));

    await waitFor(() => expect(llmPuts).toBe(1));
    // The save says it created a version that is not live — not "Saved successfully".
    await waitFor(() => expect(toastSuccess).toHaveBeenCalled());
    expect(String(toastSuccess.mock.calls[0]![0])).toBe("Saved as v4 — not live yet");
    await waitFor(() => expect(screen.queryByTestId("model-type-select")).not.toBeInTheDocument());
  });

  it("stays on the stage when the save it asked for fails", async () => {
    server.use(
      http.put("*/llmstore/llms/:id", () => HttpResponse.json({ message: "boom" }, { status: 500 })),
    );
    renderStudio();
    const user = userEvent.setup();
    await dirtyLlmEditor(user);

    await selectStage(user, RULES_STAGE);
    await user.click(await screen.findByTestId("unsaved-save"));

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(screen.getAllByTestId("model-type-select")[0]).toHaveValue("azure-openai");
  });
});

describe("Agent Studio — leaving the editor on mobile", () => {
  it("asks before the tab bar unmounts an edited editor, and Cancel keeps it", async () => {
    renderStudio();
    const user = userEvent.setup();
    await dirtyLlmEditor(user);
    await user.click(screen.getByTestId("mobile-tab-chat"));
    await user.click(await screen.findByTestId("unsaved-cancel"));
    expect(screen.getAllByTestId("model-type-select")[0]).toHaveValue("azure-openai");
  });

  it("discards and leaves on confirm", async () => {
    renderStudio();
    const user = userEvent.setup();
    await dirtyLlmEditor(user);
    await user.click(screen.getByTestId("mobile-tab-chat"));
    await user.click(await screen.findByTestId("unsaved-confirm"));
    await waitFor(() => expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument());
  });

  it("changes tab instantly when the editor is clean", async () => {
    renderStudio();
    const user = userEvent.setup();
    await selectStage(user, LLM_STAGE);
    await screen.findAllByTestId("model-type-select");
    await user.click(screen.getByTestId("mobile-tab-chat"));
    expect(screen.queryByTestId("unsaved-confirm")).not.toBeInTheDocument();
  });
});

describe("Agent Studio — Save & Test", () => {
  it("offers Save & Test beside Save on an editable stage", async () => {
    renderStudio();
    const user = userEvent.setup();
    await selectStage(user, LLM_STAGE);
    await screen.findAllByTestId("model-type-select");
    expect(screen.getAllByTestId("save-test-btn").length).toBeGreaterThan(0);
  });
});

describe("Agent Studio — the chat beside the editor", () => {
  it("is bound to the studio's agent, not whichever agent was chatted with last", async () => {
    useChatStore.getState().setSelectedAgent("some-other-agent", "Other");
    renderStudio();
    await waitFor(() => expect(useChatStore.getState().selectedAgentId).toBe("agent1"));
    expect(useChatStore.getState().selectedAgentName).toBeTruthy();
  });
});

describe("Agent Studio — workflow switcher", () => {
  function twoWorkflows() {
    server.use(
      http.get("*/agentstore/agents/:id", ({ params }) => {
        // The listing lives under the same path shape; let it through.
        if (params.id === "descriptors") return;
        return HttpResponse.json({
          workflows: [
            "eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=2",
            "eddi://ai.labs.workflow/workflowstore/workflows/wf2?version=1",
          ],
          channels: [],
        });
      }),
      http.get("*/workflowstore/workflows/:id", ({ params }) => {
        if (params.id === "descriptors") return;
        return HttpResponse.json({
          workflowSteps:
            params.id === "wf2"
              ? [{ type: "eddi://ai.labs.output", extensions: {}, config: { uri: "eddi://ai.labs.output/outputstore/outputsets/out9?version=1" } }]
              : [
                  { type: "eddi://ai.labs.rules", extensions: {}, config: { uri: "eddi://ai.labs.rules/rulestore/rulesets/beh1?version=1" } },
                  { type: "eddi://ai.labs.llm", extensions: {}, config: { uri: "eddi://ai.labs.llm/llmstore/llms/llm1?version=1" } },
                ],
        });
      }),
    );
  }

  it("lets the studio edit an agent's second workflow", async () => {
    twoWorkflows();
    renderStudio();
    const user = userEvent.setup();
    const switcher = (await screen.findAllByTestId("studio-workflow-switcher"))[0]!;
    await screen.findAllByTestId("stage-1");

    await user.selectOptions(switcher, "1");
    await waitFor(() => expect(screen.queryAllByTestId("stage-1")).toHaveLength(0));
    expect(screen.getAllByTestId("stage-0").length).toBeGreaterThan(0);

    await user.selectOptions(switcher, "0");
    await waitFor(() => expect(screen.getAllByTestId("stage-1").length).toBeGreaterThan(0));
  });

  it("gives the desktop and mobile selectors their own ids, each labelled by its own label", async () => {
    twoWorkflows();
    renderStudio();
    const selects = await screen.findAllByTestId("studio-workflow-switcher");
    expect(selects).toHaveLength(2);
    expect(selects[0]!.id).not.toBe(selects[1]!.id);
    for (const select of selects) {
      expect(document.querySelector(`label[for="${select.id}"]`)).not.toBeNull();
    }
  });

  it("is not shown for an agent with a single workflow", async () => {
    renderStudio();
    await screen.findAllByTestId("stage-0");
    expect(screen.queryByTestId("studio-workflow-switcher")).not.toBeInTheDocument();
  });

  it("asks before switching workflow away from an edited stage", async () => {
    twoWorkflows();
    renderStudio();
    const user = userEvent.setup();
    await dirtyLlmEditorInTwoWorkflowAgent(user);
    await user.selectOptions(screen.getAllByTestId("studio-workflow-switcher")[0]!, "1");
    expect(await screen.findByTestId("unsaved-confirm")).toBeInTheDocument();
    // Still on the first workflow.
    expect(within(document.body).getAllByTestId("stage-1").length).toBeGreaterThan(0);
  });
});

async function dirtyLlmEditorInTwoWorkflowAgent(user: ReturnType<typeof userEvent.setup>) {
  // In the two-workflow fixture the LLM step is stage 1.
  await user.click((await screen.findAllByTestId("stage-1"))[0]!);
  const select = (await screen.findAllByTestId("model-type-select"))[0]!;
  await user.selectOptions(select, "azure-openai");
  await waitFor(() => expect(screen.getAllByTestId("dirty-indicator").length).toBeGreaterThan(0));
}
