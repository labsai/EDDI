import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";

const mockMutate = vi.fn();
vi.mock("@/hooks/use-groups", () => ({
  useUpdateGroup: () => ({ mutate: mockMutate, isPending: false }),
}));

// The facilitator picker needs a stable agent list; the real hook would hit MSW
// and reorder by lastModifiedOn, which says nothing about this component.
vi.mock("@/hooks/use-agents", () => ({
  useAgentDescriptors: () => ({
    data: [{ resource: "eddi://ai.labs.agent/agentstore/agents/agent-a?version=1", name: "Agent A" }],
  }),
  groupAgentsByName: () => [{ id: "agent-a", name: "Agent A" }],
}));

import { GroupAdvancedEditor } from "@/components/groups/group-advanced-editor";
import type { AgentGroupConfiguration } from "@/lib/api/groups";

function makeConfig(overrides: Partial<AgentGroupConfiguration> = {}): AgentGroupConfiguration {
  return {
    name: "G",
    style: "ROUND_TABLE",
    maxRounds: 1,
    members: [],
    phases: [],
    ...overrides,
  } as AgentGroupConfiguration;
}

/** The real GroupTaskConfig shape — a partial one type-errors, and would also
 *  hide whether save carries the untouched fields through. */
const TASK_LIST_CONFIG = {
  allowAgentTaskCreation: true,
  maxAgentAddedTasksPerDiscussion: 20,
  maxPerTurn: 3,
};

const savedConfig = () => mockMutate.mock.calls[0]![0].config as AgentGroupConfiguration;

function render(config = makeConfig()) {
  renderWithProviders(
    <GroupAdvancedEditor config={config} groupId="g1" groupVersion={1} onDone={vi.fn()} />,
  );
}

describe("GroupAdvancedEditor", () => {
  beforeEach(() => mockMutate.mockClear());

  it("saves nothing enabled as absent blocks, not disabled ones", () => {
    render();
    fireEvent.click(screen.getByTestId("adv-save"));

    const saved = savedConfig();
    // `undefined` rather than `{enabled: false}` — a half-populated block is
    // something the backend still has to interpret.
    expect(saved.contextWindow).toBeUndefined();
    expect(saved.retroConfig).toBeUndefined();
    expect(saved.artifactConfig).toBeUndefined();
    expect(saved.facilitator).toBeUndefined();
  });

  it("turns on the transcript window with its defaults", () => {
    render();
    fireEvent.click(screen.getByTestId("adv-window-enable"));
    fireEvent.click(screen.getByTestId("adv-save"));

    expect(savedConfig().contextWindow).toMatchObject({
      enabled: true,
      maxRecentEntries: 30,
      summarizeOverflow: true,
    });
  });

  it("clamps a non-positive entry count to the default instead of saving it", () => {
    render();
    fireEvent.click(screen.getByTestId("adv-window-enable"));
    fireEvent.change(screen.getByTestId("adv-window-entries"), { target: { value: "0" } });
    fireEvent.click(screen.getByTestId("adv-save"));

    // 0 would make the backend silently fall back to 30 anyway; save what will
    // actually run rather than a number the UI showed and the engine ignored.
    expect(savedConfig().contextWindow?.maxRecentEntries).toBe(30);
  });

  it("caps retro lessons at the documented ceiling", () => {
    render();
    fireEvent.click(screen.getByTestId("adv-retro-enable"));
    fireEvent.change(screen.getByTestId("adv-retro-per-run"), { target: { value: "999" } });
    fireEvent.click(screen.getByTestId("adv-save"));

    expect(savedConfig().retroConfig?.maxLessonsPerRun).toBe(20);
  });

  it("preserves artifact validators it does not surface", () => {
    const config = makeConfig({
      artifactConfig: {
        allowArtifactTools: true,
        maxArtifactsPerDiscussion: 5,
        validators: [{ kind: "MAX_LENGTH", spec: "1000" }],
      },
    } as Partial<AgentGroupConfiguration>);
    render(config);
    fireEvent.change(screen.getByTestId("adv-artifacts-max"), { target: { value: "8" } });
    fireEvent.click(screen.getByTestId("adv-save"));

    const saved = savedConfig();
    expect(saved.artifactConfig?.maxArtifactsPerDiscussion).toBe(8);
    // This editor has no validator UI; dropping them on save would silently
    // delete a rule the group depends on.
    expect(saved.artifactConfig?.validators).toHaveLength(1);
  });

  it("blocks save when the facilitator has no agent", () => {
    render();
    fireEvent.click(screen.getByTestId("adv-facilitator-enable"));

    expect(screen.getByTestId("adv-facilitator-agent-error")).toBeInTheDocument();
    expect(screen.getByTestId("adv-save")).toBeDisabled();

    fireEvent.change(screen.getByTestId("adv-facilitator-agent"), { target: { value: "agent-a" } });
    expect(screen.getByTestId("adv-save")).not.toBeDisabled();
  });

  it("blocks a mid-phase move that the chosen checkpoint could never play", () => {
    render();
    fireEvent.click(screen.getByTestId("adv-facilitator-enable"));
    fireEvent.change(screen.getByTestId("adv-facilitator-agent"), { target: { value: "agent-a" } });

    // Default checkpoint is EACH_PHASE, which only runs once the phase is over —
    // so END_PHASE can never fire.
    fireEvent.click(screen.getByTestId("adv-facilitator-move-END_PHASE"));
    expect(screen.getByTestId("adv-facilitator-midphase-error")).toBeInTheDocument();
    expect(screen.getByTestId("adv-save")).toBeDisabled();

    fireEvent.change(screen.getByTestId("adv-facilitator-checkpoint"), {
      target: { value: "EACH_REPEAT" },
    });
    expect(screen.queryByTestId("adv-facilitator-midphase-error")).not.toBeInTheDocument();
    expect(screen.getByTestId("adv-save")).not.toBeDisabled();
  });

  it("requires an escalation target, and drops it when the move is removed", () => {
    render();
    fireEvent.click(screen.getByTestId("adv-facilitator-enable"));
    fireEvent.change(screen.getByTestId("adv-facilitator-agent"), { target: { value: "agent-a" } });
    fireEvent.click(screen.getByTestId("adv-facilitator-move-ESCALATE_HUMAN"));

    expect(screen.getByTestId("adv-save")).toBeDisabled();
    fireEvent.change(screen.getByTestId("adv-facilitator-escalate"), {
      target: { value: "director@acme.com" },
    });
    expect(screen.getByTestId("adv-save")).not.toBeDisabled();

    // Un-allow the move: the target field hides, but its draft survives in state.
    // Saving it would persist someone to escalate to for a move that cannot happen.
    fireEvent.click(screen.getByTestId("adv-facilitator-move-ESCALATE_HUMAN"));
    fireEvent.click(screen.getByTestId("adv-save"));
    expect(savedConfig().facilitator?.escalateTo).toBeNull();
  });

  it("hides assignment mode for a non-TASK_FORCE style", () => {
    render(
      makeConfig({
        taskListConfig: TASK_LIST_CONFIG,
      } as Partial<AgentGroupConfiguration>),
    );
    expect(screen.queryByTestId("adv-assignment-mode")).not.toBeInTheDocument();
  });

  it("switches a TASK_FORCE to bidding without rebuilding the rest of the block", () => {
    render(
      makeConfig({
        style: "TASK_FORCE",
        taskListConfig: TASK_LIST_CONFIG,
      } as Partial<AgentGroupConfiguration>),
    );
    fireEvent.change(screen.getByTestId("adv-assignment-mode"), { target: { value: "BID" } });
    fireEvent.click(screen.getByTestId("adv-save"));

    const saved = savedConfig();
    expect(saved.taskListConfig?.assignmentMode).toBe("BID");
    expect(saved.taskListConfig?.maxPerTurn).toBe(3);
  });
});
