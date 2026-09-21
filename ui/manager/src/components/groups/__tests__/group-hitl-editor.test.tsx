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

function makeConfig(overrides: Partial<AgentGroupConfiguration> = {}): AgentGroupConfiguration {
  return {
    name: "G",
    style: "ROUND_TABLE",
    maxRounds: 1,
    members: [],
    phases: [phase("Initial Opinions"), phase("Synthesis")],
    ...overrides,
  } as AgentGroupConfiguration;
}

describe("GroupHitlEditor", () => {
  beforeEach(() => mockMutate.mockReset());

  it("enabling approval and checking a phase saves hitlConfig + per-phase requiresApproval", () => {
    renderWithProviders(
      <GroupHitlEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("group-hitl-enable"));
    fireEvent.click(screen.getByTestId("group-hitl-phase-Synthesis"));
    fireEvent.click(screen.getByTestId("group-hitl-save"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    const payload = mockMutate.mock.calls[0]![0];
    expect(payload).toMatchObject({ id: "g1", version: 2 });
    expect(payload.config.hitlConfig).toMatchObject({ timeoutPolicy: "WAIT_INDEFINITELY", granularity: "PHASE" });
    const synthesis = payload.config.phases.find((p: DiscussionPhase) => p.name === "Synthesis");
    const initial = payload.config.phases.find((p: DiscussionPhase) => p.name === "Initial Opinions");
    expect(synthesis.requiresApproval).toBe(true);
    expect(initial.requiresApproval).toBe(false);
  });

  it("disabling approval clears hitlConfig and every phase's requiresApproval", () => {
    const config = makeConfig({
      phases: [phase("Initial Opinions"), phase("Synthesis", true)],
      hitlConfig: { timeoutPolicy: "WAIT_INDEFINITELY", granularity: "PHASE", onTaskRejection: "FAIL", approvalTimeout: null },
    });
    renderWithProviders(
      <GroupHitlEditor config={config} groupId="g1" groupVersion={3} onDone={vi.fn()} />,
    );
    // Enable starts checked (hitlConfig present); uncheck it.
    fireEvent.click(screen.getByTestId("group-hitl-enable"));
    fireEvent.click(screen.getByTestId("group-hitl-save"));

    const payload = mockMutate.mock.calls[0]![0];
    expect(payload.config.hitlConfig).toBeUndefined();
    expect(payload.config.phases.every((p: DiscussionPhase) => p.requiresApproval === false)).toBe(true);
  });

  it("blocks save when a finite timeout policy has an invalid duration", () => {
    renderWithProviders(
      <GroupHitlEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("group-hitl-enable"));
    fireEvent.change(screen.getByTestId("group-hitl-timeout-policy"), { target: { value: "AUTO_REJECT" } });
    // Policy switch seeds PT15M; overwrite with an invalid value.
    fireEvent.change(screen.getByTestId("group-hitl-approval-timeout"), { target: { value: "15m" } });
    fireEvent.click(screen.getByTestId("group-hitl-save"));

    expect(mockMutate).not.toHaveBeenCalled();
    expect(screen.getByTestId("group-hitl-save")).toBeDisabled();
  });

  it("blocks save when approval is enabled but no phase is selected", () => {
    renderWithProviders(
      <GroupHitlEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("group-hitl-enable"));
    // Enabled with no phase checked → save blocked and a hint shown (a config
    // that looks gated but would never pause).
    expect(screen.getByTestId("group-hitl-save")).toBeDisabled();
    expect(screen.getByTestId("group-hitl-no-phase")).toBeInTheDocument();
    fireEvent.click(screen.getByTestId("group-hitl-save"));
    expect(mockMutate).not.toHaveBeenCalled();
    // Selecting a phase unblocks save.
    fireEvent.click(screen.getByTestId("group-hitl-phase-Synthesis"));
    expect(screen.getByTestId("group-hitl-save")).not.toBeDisabled();
  });
});
