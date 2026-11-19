import { describe, it, expect, vi } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
import { useState } from "react";
import { renderWithProviders } from "@eddi/app-kit/test/test-utils";
import { HandoffConfigSection } from "@eddi/app-kit/components/groups/handoff-config-section";
import { handoffProblems, type GroupHandoffConfig } from "@eddi/app-kit/lib/api/team-handoffs";

/** Controlled harness: the section is a pure view of `value`. */
function Harness({ initial, spy }: { initial: GroupHandoffConfig | null; spy: (next: GroupHandoffConfig | null) => void }) {
  const [value, setValue] = useState<GroupHandoffConfig | null>(initial);
  return (
    <HandoffConfigSection
      value={value}
      onChange={(next) => {
        spy(next);
        setValue(next);
      }}
    />
  );
}

const last = (spy: ReturnType<typeof vi.fn>) => spy.mock.calls[spy.mock.calls.length - 1]![0] as GroupHandoffConfig | null;

describe("HandoffConfigSection", () => {
  it("is off by default and writes nothing until ticked", () => {
    const spy = vi.fn();
    renderWithProviders(<Harness initial={null} spy={spy} />);
    expect(screen.getByTestId("adv-handoff-enabled")).not.toBeChecked();
    expect(screen.queryByTestId("adv-handoff-add-target")).not.toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it("ticking creates an empty config and unticking drops the whole block (absence is off)", () => {
    const spy = vi.fn();
    renderWithProviders(<Harness initial={null} spy={spy} />);
    fireEvent.click(screen.getByTestId("adv-handoff-enabled"));
    expect(last(spy)).toEqual({ targets: [], accept: { from: [] } });
    fireEvent.click(screen.getByTestId("adv-handoff-enabled"));
    expect(last(spy)).toBeNull();
  });

  it("adds a target and edits its alias and group id", () => {
    const spy = vi.fn();
    renderWithProviders(<Harness initial={{ targets: [] }} spy={spy} />);
    fireEvent.click(screen.getByTestId("adv-handoff-add-target"));
    const row = screen.getByTestId("adv-handoff-target-0");
    fireEvent.change(row.querySelector('input[aria-label="alias"]')!, { target: { value: "account-desk" } });
    fireEvent.change(row.querySelector('input[aria-label="target group id"]')!, { target: { value: "g-desk" } });
    expect(last(spy)!.targets).toEqual([{ alias: "account-desk", groupId: "g-desk", description: "" }]);
  });

  it("keeps a field it does not show (maxPerDiscussion) through an edit", () => {
    const spy = vi.fn();
    renderWithProviders(
      <Harness initial={{ targets: [{ alias: "desk", groupId: "g" }], maxPerDiscussion: 7, maxPerTurn: 3 }} spy={spy} />,
    );
    fireEvent.click(screen.getByTestId("adv-handoff-add-target"));
    expect(last(spy)).toMatchObject({ maxPerDiscussion: 7, maxPerTurn: 3 });
  });

  it("adds an accepted source in REVIEW mode by default and lets it be set to AUTO", () => {
    const spy = vi.fn();
    renderWithProviders(<Harness initial={{ accept: { from: [] } }} spy={spy} />);
    fireEvent.click(screen.getByTestId("adv-handoff-add-accept"));
    expect(last(spy)!.accept!.from).toEqual([{ source: "", mode: "REVIEW" }]);
    const row = screen.getByTestId("adv-handoff-accept-0");
    fireEvent.change(row.querySelector('input[aria-label="agent:<id> or group:<id>"]')!, { target: { value: "agent:abc" } });
    fireEvent.change(row.querySelector("select")!, { target: { value: "AUTO" } });
    expect(last(spy)!.accept!.from).toEqual([{ source: "agent:abc", mode: "AUTO" }]);
  });

  it("offers decision rules only once a team exists, and the rule picks from the declared aliases", () => {
    const spy = vi.fn();
    const { unmount } = renderWithProviders(<Harness initial={{ targets: [] }} spy={spy} />);
    expect(screen.queryByTestId("adv-handoff-add-rule")).not.toBeInTheDocument();
    unmount();

    renderWithProviders(<Harness initial={{ targets: [{ alias: "desk", groupId: "g" }] }} spy={spy} />);
    fireEvent.click(screen.getByTestId("adv-handoff-add-rule"));
    expect(last(spy)!.onDecision).toEqual([
      { target: "desk", decisionTypes: ["VOTE"], requireWinner: true, subjectTemplate: "", descriptionTemplate: "" },
    ]);
  });

  it("explains every shape problem in a sentence, and none for a valid config", () => {
    const bad: GroupHandoffConfig = {
      targets: [
        { alias: "Bad Alias", groupId: "" },
        { alias: "ok", groupId: "g" },
        { alias: "ok", groupId: "g" },
      ],
      accept: { from: [{ source: "group:*" }, { source: "agent:x" }, { source: "agent:x" }] },
      onDecision: [{ target: "missing", decisionTypes: [], subjectTemplate: " " }],
    };
    renderWithProviders(<Harness initial={bad} spy={vi.fn()} />);
    const text = screen.getByTestId("adv-handoff-problems").textContent!;
    expect(text).toContain("Team 1: the alias must be 1-40 characters");
    expect(text).toContain("Team 1: enter the id of the group");
    expect(text).toContain("Team 3: the alias 'ok' is used twice.");
    expect(text).toContain("Accepted source 1 must be agent:<id> or group:<id>");
    expect(text).toContain("Accepted source 3 is listed twice.");
    expect(text).toContain("Decision rule 1: pick one of the teams listed above.");
    expect(text).toContain("Decision rule 1: pick at least one decision type.");
    expect(text).toContain("Decision rule 1: the subject is required.");
  });
});

describe("handoffProblems", () => {
  it("is empty for null and for a well-formed config", () => {
    expect(handoffProblems(null)).toEqual([]);
    expect(
      handoffProblems({
        targets: [{ alias: "account-desk", groupId: "g" }],
        toolParticipants: "ROLE:judge",
        accept: { from: [{ source: "group:abc" }, { source: "agent:def" }] },
        onDecision: [{ target: "account-desk", decisionTypes: ["VOTE"], subjectTemplate: "s" }],
      }),
    ).toEqual([]);
  });

  it("refuses wildcards, an empty role and more than twenty teams, mirroring the backend", () => {
    expect(handoffProblems({ accept: { from: [{ source: "agent:*" }] } }).map((p) => p.code)).toEqual(["source"]);
    expect(handoffProblems({ toolParticipants: "ROLE:" }).map((p) => p.code)).toEqual(["role"]);
    const many = Array.from({ length: 21 }, (_, i) => ({ alias: `t${i}`, groupId: "g" }));
    expect(handoffProblems({ targets: many }).map((p) => p.code)).toContain("tooManyTargets");
  });

  it("does not check that a target group exists (an imported agent may name one this instance lacks)", () => {
    expect(handoffProblems({ targets: [{ alias: "elsewhere", groupId: "no-such-group" }] })).toEqual([]);
  });
});
