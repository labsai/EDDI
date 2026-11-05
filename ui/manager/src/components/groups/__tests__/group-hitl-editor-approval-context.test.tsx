import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";

const mockMutate = vi.fn();
vi.mock("@/hooks/use-groups", () => ({
  useUpdateGroup: () => ({ mutate: mockMutate, isPending: false }),
}));

import { GroupHitlEditor } from "@/components/groups/group-hitl-editor";
import type { AgentGroupConfiguration, DiscussionPhase } from "@/lib/api/groups";

function phase(name: string, requiresApproval = false): DiscussionPhase {
  return {
    name,
    type: "OPINION",
    participants: "ALL",
    turnOrder: "SEQUENTIAL",
    contextScope: "NONE",
    targetEachPeer: false,
    inputTemplate: null,
    repeats: 1,
    requiresApproval,
  };
}

function config(overrides: Partial<AgentGroupConfiguration> = {}): AgentGroupConfiguration {
  return {
    name: "G",
    style: "ROUND_TABLE",
    maxRounds: 1,
    members: [],
    phases: [phase("Initial Opinions"), phase("Synthesis", true)],
    hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", granularity: "PHASE", onTaskRejection: "FAIL", approvalTimeout: null },
    ...overrides,
  } as AgentGroupConfiguration;
}

describe("GroupHitlEditor — approval context", () => {
  beforeEach(() => mockMutate.mockReset());

  it("authoring a block saves it with the group's hitlConfig", () => {
    renderWithProviders(<GroupHitlEditor config={config()} groupId="g1" groupVersion={2} onDone={vi.fn()} />);

    fireEvent.click(screen.getByTestId("group-approval-context-add"));
    fireEvent.change(screen.getByTestId("group-approval-context-label-0"), { target: { value: "Objection" } });
    fireEvent.change(screen.getByTestId("group-approval-context-template-0"), {
      target: { value: "{group.lastByRole.DEVIL_ADVOCATE.content}" },
    });
    fireEvent.click(screen.getByTestId("group-hitl-save"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    expect(mockMutate.mock.calls[0]![0].config.hitlConfig.approvalContext).toEqual([
      { label: "Objection", template: "{group.lastByRole.DEVIL_ADVOCATE.content}", maxChars: null },
    ]);
  });

  it("saving the editor keeps a context that was authored elsewhere (JSON, an import)", () => {
    const existing = [{ label: "Draft", template: "{group.artifacts.draft.content}", maxChars: 800 }];
    renderWithProviders(
      <GroupHitlEditor
        config={config({ hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", granularity: "PHASE", approvalContext: existing } })}
        groupId="g1"
        groupVersion={2}
        onDone={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByTestId("group-hitl-save"));

    expect(mockMutate.mock.calls[0]![0].config.hitlConfig.approvalContext).toEqual(existing);
  });

  it("an unsavable block disables Save", () => {
    renderWithProviders(
      <GroupHitlEditor
        config={config({
          hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", granularity: "PHASE", approvalContext: [{ label: "", template: "x" }] },
        })}
        groupId="g1"
        groupVersion={2}
        onDone={vi.fn()}
      />,
    );

    expect((screen.getByTestId("group-hitl-save") as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByTestId("group-hitl-save"));
    expect(mockMutate).not.toHaveBeenCalled();
  });

  it("without any block nothing extra is saved but an explicit null", () => {
    renderWithProviders(<GroupHitlEditor config={config()} groupId="g1" groupVersion={2} onDone={vi.fn()} />);
    fireEvent.click(screen.getByTestId("group-hitl-save"));
    expect(mockMutate.mock.calls[0]![0].config.hitlConfig.approvalContext).toBeNull();
  });
});
