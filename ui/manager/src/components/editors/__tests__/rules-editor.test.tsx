import { describe, it, expect, vi, beforeEach } from "vitest";
import { fireEvent, screen } from "@testing-library/react";
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

/** The type switch asks first when the condition holds user input. */
async function confirmTypeChange(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Change type" }));
}

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
    await confirmTypeChange(user);

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
    await confirmTypeChange(user);
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

describe("RulesEditor sizematcher preset", () => {
  it("presets only integer bounds SizeMatcher can parse", async () => {
    // The preset wrote min:"" and max:"", and SizeMatcher.setConfigs calls
    // Integer.parseInt on every one of those keys it finds — the save 400'd.
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={populatedConfig} onChange={onChange} />);
    await user.selectOptions(screen.getByTestId("condition-type-select"), "sizematcher");
    await confirmTypeChange(user);
    const saved = onChange.mock.lastCall![0] as RulesConfig;
    const configs = saved.behaviorGroups[0]!.behaviorRules![0]!.conditions![0]!.configs!;
    for (const key of ["min", "max", "equal"]) {
      if (key in configs) expect(configs[key]).toMatch(/^-?\d+$/);
    }
    expect(configs).toEqual({ valuePath: "", min: "1" });
  });
});

describe("RulesEditor sizematcher bounds", () => {
  const sizeConfig = (configs: Record<string, string>): RulesConfig => ({
    appendActions: false,
    expressionsAsActions: false,
    behaviorGroups: [
      {
        name: "g",
        behaviorRules: [{ name: "r", actions: ["a"], conditions: [{ type: "sizematcher", configs }] }],
      },
    ],
  });
  const lastConfigs = (onChange: ReturnType<typeof vi.fn>) =>
    (onChange.mock.lastCall![0] as RulesConfig).behaviorGroups[0]!.behaviorRules![0]!.conditions![0]!.configs!;

  it("never writes an empty bound: a cleared bound is stored as -1 (no bound)", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={sizeConfig({ valuePath: "p", min: "1" })} onChange={onChange} />);
    await user.clear(screen.getByDisplayValue("1"));
    expect(lastConfigs(onChange).min).toBe("-1");
  });

  it("shows a -1 bound as an empty 'no limit' field", () => {
    renderWithProviders(<RulesEditor data={sizeConfig({ valuePath: "p", max: "-1" })} onChange={vi.fn()} />);
    expect(screen.queryByDisplayValue("-1")).not.toBeInTheDocument();
    expect(screen.getByPlaceholderText("no limit")).toHaveValue("");
  });

  it("stores -1 when an empty added key is renamed to a bound", () => {
    const onChange = vi.fn();
    renderWithProviders(<RulesEditor data={sizeConfig({ valuePath: "p", key1: "" })} onChange={onChange} />);
    fireEvent.change(screen.getByDisplayValue("key1"), { target: { value: "max" } });
    expect(lastConfigs(onChange)).toEqual({ valuePath: "p", max: "-1" });
  });

  it("flags a bound that is not a whole number", () => {
    renderWithProviders(<RulesEditor data={sizeConfig({ valuePath: "p", min: "abc" })} onChange={vi.fn()} />);
    expect(screen.getByDisplayValue("abc")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText(/whole number/)).toBeInTheDocument();
  });

  it("stores a typed bound without surrounding whitespace", () => {
    // SizeMatcher calls Integer.parseInt without trimming: " 2 " fails the save.
    const onChange = vi.fn();
    renderWithProviders(<RulesEditor data={sizeConfig({ valuePath: "p", min: "1" })} onChange={onChange} />);
    fireEvent.change(screen.getByDisplayValue("1"), { target: { value: " 2 " } });
    expect(lastConfigs(onChange)).toEqual({ valuePath: "p", min: "2" });
  });

  it("flags a stored bound Integer.parseInt would refuse", () => {
    renderWithProviders(
      <RulesEditor data={sizeConfig({ valuePath: "p", min: " 2 ", max: "2147483648" })} onChange={vi.fn()} />,
    );
    expect(screen.getByDisplayValue("2147483648")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByDisplayValue(" 2 ", { normalizer: (v) => v })).toHaveAttribute("aria-invalid", "true");
  });

  it("accepts the int range limits", () => {
    renderWithProviders(
      <RulesEditor data={sizeConfig({ valuePath: "p", min: "-2147483648", max: "2147483647" })} onChange={vi.fn()} />,
    );
    expect(screen.getByDisplayValue("2147483647")).not.toHaveAttribute("aria-invalid");
    expect(screen.getByDisplayValue("-2147483648")).not.toHaveAttribute("aria-invalid");
  });
});

const ruleWith = (name: string, conditions: RulesConfig["behaviorGroups"][number]["behaviorRules"][number]["conditions"], actions: string[] = []) => ({
  name,
  actions,
  conditions,
});
const configWith = (...groups: RulesConfig["behaviorGroups"]): RulesConfig => ({
  appendActions: false,
  expressionsAsActions: false,
  behaviorGroups: groups,
});
const lastSaved = (fn: ReturnType<typeof vi.fn>) => fn.mock.lastCall![0] as RulesConfig;

describe("RulesEditor condition type switch", () => {
  const pristine = configWith({
    name: "g",
    executionStrategy: "executeUntilFirstSuccess",
    behaviorRules: [
      ruleWith("r", [{ type: "inputmatcher", configs: { expressions: "", occurrence: "currentStep" } }]),
    ],
  });

  it("switches at once when the condition still holds only its presets", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={pristine} onChange={onChange} />);
    await user.selectOptions(screen.getByTestId("condition-type-select"), "dependency");
    expect(screen.queryByRole("button", { name: "Change type" })).not.toBeInTheDocument();
    expect(lastSaved(onChange).behaviorGroups[0]!.behaviorRules[0]!.conditions[0]!.type).toBe("dependency");
  });

  it("asks before wiping what the user entered, and keeps it on cancel", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={populatedConfig} onChange={onChange} />);
    await user.selectOptions(screen.getByTestId("condition-type-select"), "dependency");
    expect(await screen.findByRole("button", { name: "Change type" })).toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByTestId("condition-type-select")).toHaveValue("inputmatcher");
  });
});

describe("RulesEditor config keys and enums", () => {
  it("adds a config under a free key instead of overwriting one", async () => {
    // key${entries.length} with {key1} (key0 deleted) produced key1 again.
    const onChange = vi.fn();
    const user = userEvent.setup();
    const cfg = configWith({
      name: "g",
      behaviorRules: [ruleWith("r", [{ type: "dependency", configs: { key1: "keep" } }])],
    });
    renderWithProviders(<RulesEditor data={cfg} onChange={onChange} />);
    await user.click(screen.getByRole("button", { name: "Add config" }));
    const configs = lastSaved(onChange).behaviorGroups[0]!.behaviorRules[0]!.conditions[0]!.configs!;
    expect(configs).toEqual({ key1: "keep", key2: "" });
  });

  it("offers the engine's occurrence values as a select, not free text", () => {
    renderWithProviders(<RulesEditor data={populatedConfig} onChange={vi.fn()} />);
    const select = screen.getByTestId("config-select-occurrence") as HTMLSelectElement;
    expect(Array.from(select.options).map((o) => o.value)).toEqual(["currentStep", "lastStep", "anyStep", "never"]);
  });

  it("keeps a custom value selectable rather than rewriting it", () => {
    const cfg = configWith({
      name: "g",
      behaviorRules: [ruleWith("r", [{ type: "capabilityMatch", configs: { skill: "s", strategy: "{properties.strategy}" } }])],
    });
    renderWithProviders(<RulesEditor data={cfg} onChange={vi.fn()} />);
    expect(screen.getByTestId("config-select-strategy")).toHaveValue("{properties.strategy}");
  });
});

describe("RulesEditor lint warnings", () => {
  it("warns about a rule without an actionmatcher on lastStep", () => {
    renderWithProviders(<RulesEditor data={populatedConfig} onChange={vi.fn()} />);
    expect(screen.getByTestId("no-lastStep-actionmatcher-warning")).toBeInTheDocument();
  });

  it("does not warn once one exists, even nested in a connector", () => {
    const cfg = configWith({
      name: "g",
      behaviorRules: [
        ruleWith("direct", [{ type: "actionmatcher", configs: { actions: "a", occurrence: "lastStep" } }]),
        ruleWith("nested", [
          { type: "connector", configs: {}, conditions: [{ type: "actionmatcher", configs: { actions: "b", occurrence: "lastStep" } }] },
        ]),
      ],
    });
    renderWithProviders(<RulesEditor data={cfg} onChange={vi.fn()} />);
    expect(screen.queryByTestId("no-lastStep-actionmatcher-warning")).not.toBeInTheDocument();
  });

  it("warns that a comma list in an actionmatcher means AND", () => {
    const cfg = configWith({
      name: "g",
      behaviorRules: [ruleWith("r", [{ type: "actionmatcher", configs: { actions: "a,b", occurrence: "lastStep" } }])],
    });
    renderWithProviders(<RulesEditor data={cfg} onChange={vi.fn()} />);
    expect(screen.getByTestId("comma-actionmatcher-warning")).toHaveTextContent(/AND/);
  });

  it("does not warn about a single action", () => {
    const cfg = configWith({
      name: "g",
      behaviorRules: [ruleWith("r", [{ type: "actionmatcher", configs: { actions: "a", occurrence: "lastStep" } }])],
    });
    renderWithProviders(<RulesEditor data={cfg} onChange={vi.fn()} />);
    expect(screen.queryByTestId("comma-actionmatcher-warning")).not.toBeInTheDocument();
  });
});

describe("RulesEditor ordering", () => {
  const two = configWith(
    { name: "first", behaviorRules: [ruleWith("a", []), ruleWith("b", [])] },
    { name: "second", behaviorRules: [] },
  );

  it("moves a rule down, and disables the buttons at the edges", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={two} onChange={onChange} />);
    expect(screen.getAllByTestId("rule-move-up")[0]).toBeDisabled();
    expect(screen.getAllByTestId("rule-move-down")[1]).toBeDisabled();
    await user.click(screen.getAllByTestId("rule-move-down")[0]!);
    expect(lastSaved(onChange).behaviorGroups[0]!.behaviorRules.map((r) => r.name)).toEqual(["b", "a"]);
  });

  it("moves a rule up", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={two} onChange={onChange} />);
    await user.click(screen.getAllByTestId("rule-move-up")[1]!);
    expect(lastSaved(onChange).behaviorGroups[0]!.behaviorRules.map((r) => r.name)).toEqual(["b", "a"]);
  });

  it("moves a group", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={two} onChange={onChange} />);
    expect(screen.getAllByTestId("group-move-down")[1]).toBeDisabled();
    await user.click(screen.getAllByTestId("group-move-down")[0]!);
    expect(lastSaved(onChange).behaviorGroups.map((g) => g.name)).toEqual(["second", "first"]);
  });
});

describe("RulesEditor action tags", () => {
  it("commits text left in the box when the user clicks away", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<RulesEditor data={populatedConfig} onChange={onChange} />);
    await user.type(screen.getByRole("textbox", { name: "Actions" }), "farewell");
    expect(onChange).not.toHaveBeenCalled();
    await user.tab();
    expect(lastSaved(onChange).behaviorGroups[0]!.behaviorRules[0]!.actions).toEqual(["greet", "welcome", "farewell"]);
  });

  it("splits a comma list into separate actions", () => {
    const onChange = vi.fn();
    renderWithProviders(<RulesEditor data={populatedConfig} onChange={onChange} />);
    // A paste (or a typed comma) arrives as one change event.
    fireEvent.change(screen.getByRole("textbox", { name: "Actions" }), { target: { value: "x, y" } });
    expect(lastSaved(onChange).behaviorGroups[0]!.behaviorRules[0]!.actions).toEqual(["greet", "welcome", "x", "y"]);
  });
});
