import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { RulesEditor, type RulesConfig } from "@/components/editors/rules-editor";

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
      executionStrategy: "currentStepOnly",
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
            executionStrategy: "currentStepOnly",
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
        { name: "Empty", executionStrategy: "currentStepOnly", behaviorRules: [] },
      ],
    };
    renderWithProviders(
      <RulesEditor data={configNoRules} onChange={onChange} />
    );
    expect(screen.getByText("No rules in this group")).toBeInTheDocument();
  });
});
