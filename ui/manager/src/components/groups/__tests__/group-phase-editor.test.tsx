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

  /**
   * `min`/`max`/`step` bound the spinner, not the value — a paste survives all
   * three, and `normalizeConvergence` only applies the floor. A minRepeats above
   * the phase's own repeat count saves a config whose judge can never run.
   */
  it("clamps minRepeats to the phase's repeat count", () => {
    renderWithProviders(
      <GroupPhaseEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-convergence-enable-0"));
    // The phase declares repeats: 4.
    fireEvent.change(screen.getByTestId("phase-convergence-min-0"), { target: { value: "99" } });
    fireEvent.click(screen.getByTestId("group-phase-save"));

    expect(savedConfig().phases![0]!.convergence!.minRepeats).toBe(4);
  });

  it("stores a whole number of repeats", () => {
    renderWithProviders(
      <GroupPhaseEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-convergence-enable-0"));
    fireEvent.change(screen.getByTestId("phase-convergence-min-0"), { target: { value: "3.7" } });
    fireEvent.click(screen.getByTestId("group-phase-save"));

    expect(savedConfig().phases![0]!.convergence!.minRepeats).toBe(3);
  });

  /**
   * A config authored through the API can carry convergence on a phase that runs
   * once. A flat `disabled={!canConverge}` rendered that checkbox checked AND
   * frozen — an inert setting the UI could display but never clear.
   */
  it("lets an inert convergence block be cleared on a single-pass phase", () => {
    const config = makeConfig({
      phases: [
        phase("Synthesis", {
          repeats: 1,
          convergence: { enabled: true, minRepeats: 2, threshold: 0.8, judge: "MODERATOR" },
        }),
      ],
    });
    renderWithProviders(
      <GroupPhaseEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );

    const box = screen.getByTestId("phase-convergence-enable-0");
    expect(box).not.toBeDisabled();
    expect(box).toBeChecked();
    expect(screen.getByTestId("group-phase-editor")).toHaveTextContent(/judge can never run/i);

    fireEvent.click(box);
    fireEvent.click(screen.getByTestId("group-phase-save"));
    expect(savedConfig().phases![0]!.convergence).toBeNull();
  });

  it("still disables the control when there is nothing to clear", () => {
    const config = makeConfig({ phases: [phase("Synthesis", { repeats: 1 })] });
    renderWithProviders(
      <GroupPhaseEditor config={config} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    expect(screen.getByTestId("phase-convergence-enable-0")).toBeDisabled();
    expect(screen.getByTestId("group-phase-editor")).toHaveTextContent(/Only available on a phase that repeats/i);
  });

  it("labels every convergence control for a screen reader", () => {
    renderWithProviders(
      <GroupPhaseEditor config={makeConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
    );
    fireEvent.click(screen.getByTestId("phase-convergence-enable-0"));

    expect(screen.getByLabelText("Min repeats")).toBeInTheDocument();
    expect(screen.getByLabelText("Threshold")).toBeInTheDocument();
    expect(screen.getByLabelText("Judge")).toBeInTheDocument();
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

  // I14 — VOTE phase ballot configuration.
  describe("vote phases", () => {
    function voteConfig() {
      return makeConfig({
        style: "CUSTOM",
        phases: [phase("Ballot", { type: "VOTE", turnOrder: "PARALLEL", contextScope: "NONE" })],
      });
    }

    it("seeds a structurally valid voteConfig even with no interaction", () => {
      renderWithProviders(
        <GroupPhaseEditor config={voteConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
      );
      fireEvent.click(screen.getByTestId("group-phase-save"));

      expect(savedConfig().phases![0]!.voteConfig).toEqual({
        method: "MAJORITY",
        optionsSource: "LAST_SYNTHESIS",
        options: [],
        quorum: 0.5,
        weights: {},
        weightByConfidence: false,
        tiePolicy: "NO_DECISION",
      });
    });

    it("switching to an explicit option list reveals the textarea and saves trimmed, non-empty lines", () => {
      renderWithProviders(
        <GroupPhaseEditor config={voteConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
      );
      expect(screen.queryByTestId("phase-vote-options-0")).not.toBeInTheDocument();

      fireEvent.change(screen.getByTestId("phase-vote-options-source-0"), {
        target: { value: "EXPLICIT" },
      });
      const textarea = screen.getByTestId("phase-vote-options-0");
      fireEvent.change(textarea, { target: { value: "  Option A  \n\nOption B\n" } });
      fireEvent.click(screen.getByTestId("group-phase-save"));

      const vc = savedConfig().phases![0]!.voteConfig!;
      expect(vc.optionsSource).toBe("EXPLICIT");
      expect(vc.options).toEqual(["Option A", "Option B"]);
    });

    it("saves method, quorum, and weightByConfidence edits", () => {
      renderWithProviders(
        <GroupPhaseEditor config={voteConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
      );
      fireEvent.change(screen.getByTestId("phase-vote-method-0"), { target: { value: "APPROVAL" } });
      fireEvent.change(screen.getByTestId("phase-vote-quorum-0"), { target: { value: "0.75" } });
      fireEvent.click(screen.getByTestId("phase-vote-weight-confidence-0"));
      fireEvent.click(screen.getByTestId("group-phase-save"));

      const vc = savedConfig().phases![0]!.voteConfig!;
      expect(vc.method).toBe("APPROVAL");
      expect(vc.quorum).toBeCloseTo(0.75);
      expect(vc.weightByConfidence).toBe(true);
    });

    it("never offers HUMAN_DECIDES as a selectable tie policy — still save-time rejected by the backend", () => {
      renderWithProviders(
        <GroupPhaseEditor config={voteConfig()} groupId="g1" groupVersion={2} onDone={vi.fn()} />,
      );
      const select = screen.getByTestId("phase-vote-tie-0") as HTMLSelectElement;
      const humanOption = Array.from(select.options).find((o) => o.value === "HUMAN_DECIDES")!;
      expect(humanOption.disabled).toBe(true);
    });
  });
});
