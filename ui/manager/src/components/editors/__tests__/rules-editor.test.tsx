import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  RulesEditor,
  type RulesConfig,
  type RulesConfigInput,
} from "@/components/editors/rules-editor";

const emptyConfig: RulesConfig = {
  appendActions: false,
  expressionsAsActions: false,
  behaviorGroups: [],
};

const populatedConfig: RulesConfig = {
  appendActions: true,
  expressionsAsActions: false,
  behaviorGroups: [
    {
      name: "Greeting Group",
      executionStrategy: "executeUntilFirstSuccess",
      behaviorRules: [
        {
          name: "Greet Rule",
          actions: ["greet", "welcome"],
          conditions: [
            {
              type: "inputmatcher",
              configs: { expressions: "hello", occurrence: "currentStep" },
            },
          ],
        },
      ],
    },
  ],
};

describe("RulesEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid rules-editor", () => {
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("rules-editor")).toBeInTheDocument();
  });

  it("shows append actions checkbox", () => {
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Append Actions")).toBeInTheDocument();
  });

  it("shows expressions as actions checkbox", () => {
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Expressions as Actions")).toBeInTheDocument();
  });

  it("shows no groups message when empty", () => {
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("No Rules Groups defined")).toBeInTheDocument();
  });

  it("shows add group button", () => {
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("add-group-btn")).toBeInTheDocument();
    expect(screen.getByText("Add Group")).toBeInTheDocument();
  });

  it("hides add group button in readOnly mode", () => {
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByTestId("add-group-btn")).not.toBeInTheDocument();
  });

  it("calls onChange when add group is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("add-group-btn"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        behaviorGroups: [
          expect.objectContaining({
            name: "",
            executionStrategy: "executeUntilFirstSuccess",
            behaviorRules: [],
          }),
        ],
      })
    );
  });

  it("renders populated config with group", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("rules-group")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Greeting Group")).toBeInTheDocument();
  });

  it("renders rule within group", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("rule-editor")).toBeInTheDocument();
    expect(screen.getByDisplayValue("Greet Rule")).toBeInTheDocument();
  });

  it("renders action tags", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("greet")).toBeInTheDocument();
    expect(screen.getByText("welcome")).toBeInTheDocument();
  });

  it("renders condition editor", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("condition-editor")).toBeInTheDocument();
  });

  it("shows add rule button inside group", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("add-rule-btn")).toBeInTheDocument();
    expect(screen.getByText("Add Rule")).toBeInTheDocument();
  });

  it("shows add condition button inside rule", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("add-condition-btn")).toBeInTheDocument();
    expect(screen.getByText("Add Condition")).toBeInTheDocument();
  });

  it("toggles appendActions checkbox", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} />
    );
    const checkbox = screen.getByText("Append Actions").closest("label")!.querySelector("input")!;
    await user.click(checkbox);
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ appendActions: true })
    );
  });

  it("toggles expressionsAsActions checkbox", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RulesEditor data={emptyConfig} onChange={onChange} />
    );
    const checkbox = screen.getByText("Expressions as Actions").closest("label")!.querySelector("input")!;
    await user.click(checkbox);
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ expressionsAsActions: true })
    );
  });

  it("handles null data gracefully", () => {
    renderWithProviders(
      <RulesEditor data={null as unknown as RulesConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("rules-editor")).toBeInTheDocument();
  });

  it("shows config key-value pairs in condition", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("expressions")).toBeInTheDocument();
    expect(screen.getByDisplayValue("hello")).toBeInTheDocument();
  });

  it("shows no rules message when group has no rules", () => {
    const configNoRules: RulesConfig = {
      ...emptyConfig,
      behaviorGroups: [
        {
          name: "Empty",
          executionStrategy: "executeUntilFirstSuccess",
          behaviorRules: [],
        },
      ],
    };
    renderWithProviders(
      <RulesEditor data={configNoRules} onChange={onChange} />
    );
    expect(screen.getByText("No rules in this group")).toBeInTheDocument();
  });

  // ── Regression: backend RuleGroup.ExecutionStrategy contract ──────────────
  // Backend enum is {executeAll, executeUntilFirstSuccess}; anything else throws
  // ExecutionStrategy.valueOf(...) at rules-module instantiation (unloadable bot).
  it("defaults new groups to the backend-valid executeUntilFirstSuccess strategy", async () => {
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={emptyConfig} onChange={onChange} />);
    await user.click(screen.getByTestId("add-group-btn"));
    const arg = onChange.mock.lastCall![0] as RulesConfig;
    expect(arg.behaviorGroups[0]!.executionStrategy).toBe("executeUntilFirstSuccess");
    expect(["currentStepOnly", "lastStepOnly", "anyStep"]).not.toContain(
      arg.behaviorGroups[0]!.executionStrategy
    );
  });

  it("offers only backend-valid execution strategies in the group selector", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    const select = screen.getByTestId("group-strategy-select") as HTMLSelectElement;
    const values = Array.from(select.options)
      .map((o) => o.value)
      .filter((v) => !o_disabled(select, v));
    expect(values).toEqual(
      expect.arrayContaining(["executeUntilFirstSuccess", "executeAll"])
    );
    expect(values).not.toContain("currentStepOnly");
    expect(values).not.toContain("lastStepOnly");
    expect(values).not.toContain("anyStep");
  });

  // ── Regression: backend condition-ID contract (12 IDs, exact casing) ──────
  it("offers all 12 backend condition types with exact backend casing", () => {
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    const select = screen.getByTestId(
      "condition-type-select"
    ) as HTMLSelectElement;
    const values = Array.from(select.options).map((o) => o.value);
    for (const id of [
      "inputmatcher",
      "actionmatcher",
      "connector",
      "negation",
      "contextmatcher",
      "occurrence",
      "dynamicvaluematcher",
      "sizematcher",
      "dependency",
      "capabilityMatch",
      "contentTypeMatcher",
      "deploymentContext",
    ]) {
      expect(values).toContain(id);
    }
    // camelCase mis-casing produced unloadable rulesets — must be gone
    expect(values).not.toContain("dynamicValueMatcher");
  });

  /**
   * A rule set as EDDI serialised it before the wire name was fixed: the group's
   * list arrives as `rules`, not `behaviorRules`.
   *
   * This is what Karol saw. The editor typed the field as `behaviorRules` only,
   * so `group.behaviorRules ?? []` was always empty and every group rendered as
   * "No rules in this group" — for every rule set on the server, not just ones
   * created over the API. Nothing in the suite caught it because the MSW
   * handlers returned the spelling the editor wanted rather than the one the
   * server sent.
   */
  const legacyShapedConfig: RulesConfigInput = {
    appendActions: true,
    expressionsAsActions: false,
    behaviorGroups: [
      {
        name: "Greeting Group",
        executionStrategy: "executeUntilFirstSuccess",
        rules: [
          {
            name: "Greet Rule",
            actions: ["greet"],
            conditions: [
              {
                type: "inputmatcher",
                configs: { expressions: "hello", occurrence: "currentStep" },
              },
            ],
          },
        ],
      },
    ],
  };

  it("renders rules that arrived under the legacy 'rules' key", () => {
    renderWithProviders(
      <RulesEditor data={legacyShapedConfig} onChange={onChange} />
    );

    expect(screen.queryByText(/No rules in this group/i)).not.toBeInTheDocument();
    expect(screen.getByDisplayValue("Greet Rule")).toBeInTheDocument();
  });

  it("saves a legacy-shaped rule set back under one key, never both", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RulesEditor data={legacyShapedConfig} onChange={onChange} />
    );

    // Any edit is enough; the point is the shape of what goes out.
    await user.selectOptions(
      screen.getByTestId("condition-type-select"),
      "contentTypeMatcher"
    );

    const arg = onChange.mock.lastCall![0] as RulesConfig;
    const group = arg.behaviorGroups[0]!;
    expect(group.behaviorRules).toHaveLength(1);
    // Asserted on the runtime keys, not the type: RulesConfig no longer declares
    // `rules`, but types are erased, and a spread that carried the legacy key
    // through would still reach the server. Both keys present would let
    // Jackson's last-one-wins decide which list survives the save by field
    // order — a way to lose rules silently.
    expect(Object.keys(group)).not.toContain("rules");
  });

  it("emits backend-correct preset configs when switching to a new condition type", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <RulesEditor data={populatedConfig} onChange={onChange} />
    );
    await user.selectOptions(
      screen.getByTestId("condition-type-select"),
      "contentTypeMatcher"
    );
    const arg = onChange.mock.lastCall![0] as RulesConfig;
    const cond = arg.behaviorGroups[0]!.behaviorRules[0]!.conditions[0]!;
    expect(cond.type).toBe("contentTypeMatcher");
    expect(cond.configs).toEqual({ mimeType: "", minCount: "1" });
  });
});

/** True if the option with this value is disabled (the surfaced legacy marker). */
function o_disabled(select: HTMLSelectElement, value: string): boolean {
  const opt = Array.from(select.options).find((o) => o.value === value);
  return !!opt?.disabled;
}
