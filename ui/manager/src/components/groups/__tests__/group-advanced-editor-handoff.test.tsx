import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";

const mockMutate = vi.fn();
vi.mock("@eddi/app-kit/hooks/use-groups", () => ({
  useUpdateGroup: () => ({ mutate: mockMutate, isPending: false }),
}));
vi.mock("@eddi/app-kit/hooks/use-agents", () => ({
  useAgentDescriptors: () => ({ data: [] }),
  groupAgentsByName: () => [],
}));

import { GroupAdvancedEditor } from "@eddi/app-kit/components/groups/group-advanced-editor";
import type { AgentGroupConfiguration } from "@eddi/app-kit/lib/api/groups";

function makeConfig(overrides: Partial<AgentGroupConfiguration> = {}): AgentGroupConfiguration {
  return { name: "G", style: "ROUND_TABLE", maxRounds: 1, members: [], phases: [], ...overrides } as AgentGroupConfiguration;
}

const saved = () => mockMutate.mock.calls[0]![0].config as AgentGroupConfiguration;

function render(config: AgentGroupConfiguration) {
  renderWithProviders(<GroupAdvancedEditor config={config} groupId="g1" groupVersion={1} onDone={vi.fn()} />);
}

describe("GroupAdvancedEditor — cross-team handoff", () => {
  beforeEach(() => mockMutate.mockClear());

  it("saves a group without handoff exactly as before", () => {
    render(makeConfig());
    fireEvent.click(screen.getByTestId("adv-save"));
    expect(saved().handoffConfig).toBeUndefined();
  });

  it("carries a stored handoffConfig, including fields the form does not show, through an unrelated save", () => {
    const stored = {
      targets: [{ alias: "desk", groupId: "g-desk" }],
      maxPerDiscussion: 7,
      accept: { from: [{ source: "group:abc", mode: "AUTO" as const }] },
    };
    render(makeConfig({ handoffConfig: stored }));
    fireEvent.click(screen.getByTestId("adv-save"));
    expect(saved().handoffConfig).toEqual(stored);
  });

  it("saves a newly configured target and accepted source", () => {
    render(makeConfig());
    fireEvent.click(screen.getByTestId("adv-handoff-enabled"));
    fireEvent.click(screen.getByTestId("adv-handoff-add-target"));
    const row = screen.getByTestId("adv-handoff-target-0");
    fireEvent.change(row.querySelector('input[aria-label="alias"]')!, { target: { value: "desk" } });
    fireEvent.change(row.querySelector('input[aria-label="target group id"]')!, { target: { value: "g-desk" } });
    fireEvent.click(screen.getByTestId("adv-save"));
    expect(saved().handoffConfig?.targets).toEqual([{ alias: "desk", groupId: "g-desk", description: "" }]);
  });

  it("will not save while the handoff block has a shape problem, and says why", () => {
    render(makeConfig());
    fireEvent.click(screen.getByTestId("adv-handoff-enabled"));
    fireEvent.click(screen.getByTestId("adv-handoff-add-target")); // blank alias and group id
    fireEvent.click(screen.getByTestId("adv-save"));
    expect(mockMutate).not.toHaveBeenCalled();
    expect(screen.getByTestId("adv-handoff-problems")).toHaveTextContent("Team 1: the alias must be 1-40 characters");
  });

  it("unticking drops the block from the saved config (absence is off)", () => {
    render(makeConfig({ handoffConfig: { targets: [{ alias: "desk", groupId: "g" }] } }));
    fireEvent.click(screen.getByTestId("adv-handoff-enabled"));
    fireEvent.click(screen.getByTestId("adv-save"));
    expect(saved().handoffConfig).toBeUndefined();
  });
});
