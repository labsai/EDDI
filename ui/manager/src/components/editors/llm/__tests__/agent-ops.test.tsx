import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { withOpsField, withOpsTool } from "../agent-ops";
import { TaskAgentOpsSection } from "../task-agent-ops-section";
import { BUILT_IN_TOOLS, type LlmTask } from "../types";

describe("withOpsTool", () => {
  it("keeps the plain built-ins when it is the first whitelist entry", () => {
    const next = withOpsTool({}, "team_scorecard", true);
    expect(next.enableBuiltInTools).toBe(true);
    expect(next.builtInToolsWhitelist).toEqual([...BUILT_IN_TOOLS, "team_scorecard"]);
  });

  it("adds to an existing whitelist without duplicating", () => {
    const base: LlmTask = { builtInToolsWhitelist: ["calculator", "schedule_followup"] };
    expect(withOpsTool(base, "schedule_followup", true).builtInToolsWhitelist).toEqual([
      "calculator",
      "schedule_followup",
    ]);
  });

  it("removes only its slug and keeps built-ins on while others remain", () => {
    const next = withOpsTool(
      { enableBuiltInTools: true, builtInToolsWhitelist: ["calculator", "team_scorecard"] },
      "team_scorecard",
      false,
    );
    expect(next.builtInToolsWhitelist).toEqual(["calculator"]);
    expect(next.enableBuiltInTools).toBe(true);
  });

  it("switches built-ins off when the last listed tool is unticked, never widening to all", () => {
    const next = withOpsTool(
      { enableBuiltInTools: true, builtInToolsWhitelist: ["team_scorecard"] },
      "team_scorecard",
      false,
    );
    expect(next.enableBuiltInTools).toBe(false);
    expect(next.builtInToolsWhitelist).toBeUndefined();
  });
});

describe("withOpsField", () => {
  it("writes a field and removes it again, leaving no empty block", () => {
    const set = withOpsField({}, "followUp", "maxHorizonDays", 60);
    expect(set.agentOps).toEqual({ followUp: { maxHorizonDays: 60 } });
    expect(withOpsField(set, "followUp", "maxHorizonDays", undefined).agentOps).toBeUndefined();
  });

  it("keeps other blocks and drops an empty list", () => {
    const base: LlmTask = { agentOps: { budgetProposal: { maxPerDay: 2 }, scorecard: { groupIds: ["a"] } } };
    const next = withOpsField(base, "scorecard", "groupIds", []);
    expect(next.agentOps).toEqual({ budgetProposal: { maxPerDay: 2 } });
  });
});

describe("TaskAgentOpsSection", () => {
  it("shows the limits and the approval hint only for an enabled tool", () => {
    render(
      <TaskAgentOpsSection
        task={{ enableBuiltInTools: true, builtInToolsWhitelist: ["propose_team_budget"] }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByTestId("agent-ops-budget-gate-hint")).toBeTruthy();
    expect(screen.queryByTestId("agent-ops-followup-limits")).toBeNull();
    expect(screen.queryByTestId("agent-ops-scorecard-limits")).toBeNull();
  });

  it("ticking a tool reports the whitelist change", () => {
    const onChange = vi.fn();
    render(<TaskAgentOpsSection task={{ agentOps: {} }} onChange={onChange} />);
    fireEvent.click(screen.getByTestId("agent-ops-enable-schedule_followup"));
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ enableBuiltInTools: true }));
    expect(onChange.mock.calls[0]?.[0]?.builtInToolsWhitelist).toContain("schedule_followup");
  });
});