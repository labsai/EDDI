import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";

const mockMutate = vi.fn();
vi.mock("@/hooks/use-groups", () => ({
  useUpdateGroup: () => ({ mutate: mockMutate, isPending: false }),
}));

import { GroupPhaseEditor } from "@/components/groups/group-phase-editor";
import type { AgentGroupConfiguration, DiscussionPhase } from "@/lib/api/groups";

function phase(name: string, extra: Partial<DiscussionPhase> = {}): DiscussionPhase {
  return {
    name,
    type: "OPINION",
    participants: "ALL",
    turnOrder: "SEQUENTIAL",
    contextScope: "NONE",
    targetEachPeer: false,
    inputTemplate: null,
    repeats: 1,
    requiresApproval: false,
    ...extra,
  };
}

function makeConfig(overrides: Partial<AgentGroupConfiguration> = {}): AgentGroupConfiguration {
  return {
    name: "G",
    style: "ROUND_TABLE",
    maxRounds: 1,
    members: [],
    phases: [phase("Discussion", { repeats: 4 }), phase("Synthesis", { type: "SYNTHESIS" })],
    ...overrides,
  } as AgentGroupConfiguration;
}

const savedConfig = () => mockMutate.mock.calls[0]![0].config as AgentGroupConfiguration;

describe("GroupPhaseEditor", () => {
  beforeEach(() => mockMutate.mockReset());

  it("saves per-phase abstention", () => {
    renderWithProviders(
      <GroupPhaseEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-abstention-1"));
    fireEvent.click(screen.getByTestId("group-phase-save"));

    expect(mockMutate).toHaveBeenCalledTimes(1);
    expect(mockMutate.mock.calls[0]![0]).toMatchObject({ id: "g1", version: 2 });
    const phases = savedConfig().phases!;
    expect(phases[0]!.allowAbstention).toBeFalsy();
    expect(phases[1]!.allowAbstention).toBe(true);
  });

  it("enables convergence with the backend's defaults", () => {
    renderWithProviders(
      <GroupPhaseEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-convergence-enable-0"));
    fireEvent.click(screen.getByTestId("group-phase-save"));

    expect(savedConfig().phases![0]!.convergence).toEqual({
      enabled: true, minRepeats: 2, threshold: 0.8, judge: "MODERATOR",
    });
  });

  it("cannot enable convergence on a phase that runs once", () => {
    // One pass has nothing to compare against, so the backend's judge could
    // never run — offering the control would be a lie.
    renderWithProviders(
      <GroupPhaseEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    expect(screen.getByTestId("phase-convergence-enable-1")).toBeDisabled();
    expect(screen.getByTestId("phase-convergence-enable-0")).not.toBeDisabled();
  });

  it("normalizes a below-floor minRepeats on save", () => {
    renderWithProviders(
      <GroupPhaseEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-convergence-enable-0"));
    fireEvent.change(screen.getByTestId("phase-convergence-min-0"), { target: { value: "1" } });
    fireEvent.click(screen.getByTestId("group-phase-save"));

    expect(savedConfig().phases![0]!.convergence!.minRepeats).toBe(2);
  });

  it("stores null rather than a disabled convergence block", () => {
    const config = makeConfig({
      phases: [
        phase("Discussion", {
          repeats: 4,
          convergence: { enabled: true, minRepeats: 2, threshold: 0.8, judge: "MODERATOR" },
        }),
      ],
    });
    renderWithProviders(
      <GroupPhaseEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-convergence-enable-0"));
    fireEvent.click(screen.getByTestId("group-phase-save"));

    expect(savedConfig().phases![0]!.convergence).toBeNull();
  });

  it("warns that the SERVICE judge is not wired yet", () => {
    renderWithProviders(
      <GroupPhaseEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-convergence-enable-0"));
    expect(screen.queryByTestId("phase-convergence-service-note-0")).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId("phase-convergence-judge-0"), { target: { value: "SERVICE" } });
    expect(screen.getByTestId("phase-convergence-service-note-0")).toBeInTheDocument();
  });

  /**
   * The phase list is the shared home of `requiresApproval` too, so this editor
   * and the approval editor must not overwrite each other.
   */
  it("preserves requiresApproval set by the approval editor", () => {
    const config = makeConfig({
      phases: [phase("Discussion", { repeats: 4 }), phase("Synthesis", { requiresApproval: true })],
    });
    renderWithProviders(
      <GroupPhaseEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-abstention-0"));
    fireEvent.click(screen.getByTestId("group-phase-save"));

    expect(savedConfig().phases![1]!.requiresApproval).toBe(true);
  });

  /**
   * A preset-style group stores `phases: null` and the engine expands the preset
   * at runtime — so, like the approval editor, this one materializes the list.
   */
  it("materializes a preset group's phases", () => {
    const config = makeConfig({ phases: null, style: "PEER_REVIEW", maxRounds: 2 });
    renderWithProviders(
      <GroupPhaseEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    expect(screen.getByText("Peer Critique")).toBeInTheDocument();

    fireEvent.click(screen.getByTestId("phase-abstention-0"));
    fireEvent.click(screen.getByTestId("group-phase-save"));

    const phases = savedConfig().phases!;
    expect(phases.map((p) => p.name)).toEqual([
      "Initial Opinions", "Peer Critique", "Revision", "Synthesis",
    ]);
    expect(phases[0]!.allowAbstention).toBe(true);
  });

  it("explains itself instead of saving when a CUSTOM group has no phases", () => {
    const config = makeConfig({ phases: [], style: "CUSTOM" });
    renderWithProviders(
      <GroupPhaseEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    expect(screen.queryByTestId("group-phase-save")).not.toBeInTheDocument();
    expect(screen.getByTestId("group-phase-editor")).toHaveTextContent("no phases");
  });
});
