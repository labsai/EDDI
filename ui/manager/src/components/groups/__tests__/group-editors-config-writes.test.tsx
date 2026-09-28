import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderWithProviders } from "@/test/test-utils";

const mockMutate = vi.fn();
vi.mock("@/hooks/use-groups", () => ({
  useUpdateGroup: () => ({ mutate: mockMutate, isPending: false }),
}));

vi.mock("@/hooks/use-agents", () => ({
  useAgentDescriptors: () => ({ data: [] }),
  groupAgentsByName: () => [],
}));

import { GroupPhaseEditor } from "@/components/groups/group-phase-editor";
import { GroupAdvancedEditor } from "@/components/groups/group-advanced-editor";
import type { AgentGroupConfiguration, DiscussionPhase } from "@/lib/api/groups";

/**
 * Group editors that wrote something other than what the author entered — or
 * could not accept what the author was typing at all.
 */

const savedConfig = () => mockMutate.mock.calls[0]![0].config as AgentGroupConfiguration;

function ballot(): AgentGroupConfiguration {
  const phase: DiscussionPhase = {
    name: "Ballot",
    type: "VOTE",
    participants: "ALL",
    turnOrder: "PARALLEL",
    contextScope: "NONE",
    targetEachPeer: false,
    inputTemplate: null,
    repeats: 1,
    requiresApproval: false,
    voteConfig: {
      method: "MAJORITY",
      optionsSource: "EXPLICIT",
      options: [],
      quorum: 0.5,
      weights: {},
      weightByConfidence: false,
      tiePolicy: "NO_DECISION",
    },
  };
  return { name: "G", style: "CUSTOM", maxRounds: 1, members: [], phases: [phase] } as unknown as AgentGroupConfiguration;
}

describe("GroupPhaseEditor — explicit ballot options", () => {
  beforeEach(() => mockMutate.mockReset());

  /**
   * The textarea was bound to the CLEANED list, so the newline Enter typed and
   * the space between two words were deleted as they were typed: a second
   * option, or any option of two words, could not be entered.
   */
  it("accepts several multi-word options typed line by line", async () => {
    renderWithProviders(<GroupPhaseEditor config={ballot()} groupId="g1" groupVersion={1} onDone={vi.fn()} />);
    const box = screen.getByTestId("phase-vote-options-0");

    await userEvent.type(box, "Use pgvector{Enter}Stay on Mongo");

    expect(box).toHaveValue("Use pgvector\nStay on Mongo");
    fireEvent.click(screen.getByTestId("group-phase-save"));
    expect(savedConfig().phases![0]!.voteConfig!.options).toEqual(["Use pgvector", "Stay on Mongo"]);
  });

  it("holds the save back, and says why, while an explicit ballot has fewer than two options", async () => {
    renderWithProviders(<GroupPhaseEditor config={ballot()} groupId="g1" groupVersion={1} onDone={vi.fn()} />);
    await userEvent.type(screen.getByTestId("phase-vote-options-0"), "Only one");

    expect(screen.getByTestId("phase-vote-options-error-0")).toBeInTheDocument();
    expect(screen.getByTestId("group-phase-save")).toBeDisabled();
    fireEvent.click(screen.getByTestId("group-phase-save"));
    expect(mockMutate).not.toHaveBeenCalled();
  });
});

describe("GroupAdvancedEditor — retro limits", () => {
  beforeEach(() => mockMutate.mockReset());

  it("keeps retro fields it does not show when it saves the caps", () => {
    const config = {
      name: "G",
      style: "ROUND_TABLE",
      maxRounds: 1,
      members: [],
      phases: [],
      retroConfig: { maxLessonsPerRun: 4, maxStoredLessons: 40, maxLessonChars: 1500 },
    } as unknown as AgentGroupConfiguration;
    renderWithProviders(<GroupAdvancedEditor config={config} groupId="g1" groupVersion={1} onDone={vi.fn()} />);
    fireEvent.click(screen.getByTestId("adv-save"));

    expect(savedConfig().retroConfig).toEqual({ maxLessonsPerRun: 4, maxStoredLessons: 40, maxLessonChars: 1500 });
  });

  /**
   * The backend has no "off" for retro: a null retroConfig means the default
   * caps. An unticked box labelled "Retro lessons" read as switching it off.
   */
  it("says unticking keeps the default caps rather than turning retro off", () => {
    renderWithProviders(
      <GroupAdvancedEditor
        config={{ name: "G", style: "ROUND_TABLE", maxRounds: 1, members: [], phases: [] } as unknown as AgentGroupConfiguration}
        groupId="g1"
        groupVersion={1}
        onDone={vi.fn()}
      />,
    );
    expect(screen.getByTestId("adv-retro-hint")).toHaveTextContent(/default caps \(3 per run, 50 stored\)/);
    expect(screen.getByTestId("adv-retro-hint")).toHaveTextContent(/remove the RETRO phase/);
  });
});
