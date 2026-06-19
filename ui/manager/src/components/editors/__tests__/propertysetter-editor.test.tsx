import { describe, it, expect, vi, beforeEach } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import {
  PropertySetterEditor,
  type PropertySetterConfig,
} from "@/components/editors/propertysetter-editor";

// Mock monaco since it's externalized
vi.mock("@monaco-editor/react", () => ({
  default: vi.fn(({ value }: { value: string }) => (
    <textarea data-testid="mock-monaco" defaultValue={value} />
  )),
}));

const emptyConfig: PropertySetterConfig = {
  setOnActions: [],
};

const populatedConfig: PropertySetterConfig = {
  setOnActions: [
    {
      actions: ["greet", "welcome"],
      setProperties: [
        {
          name: "user_greeted",
          valueString: "true",
          scope: "conversation",
          override: true,
        },
        {
          name: "lang",
          valueString: "en",
          scope: "longTerm",
          override: false,
          fromObjectPath: "properties.language",
        },
      ],
    },
  ],
};

const multiSetterConfig: PropertySetterConfig = {
  setOnActions: [
    {
      actions: ["greet"],
      setProperties: [
        { name: "greeted", valueString: "true", scope: "conversation", override: true },
      ],
    },
    {
      actions: ["farewell"],
      setProperties: [
        { name: "farewell_said", valueString: "true", scope: "step", override: false },
      ],
    },
  ],
};

describe("PropertySetterEditor", () => {
  const onChange = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders with data-testid propertysetter-editor", () => {
    renderWithProviders(
      <PropertySetterEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("propertysetter-editor")).toBeInTheDocument();
  });

  it("shows Property Setters heading", () => {
    renderWithProviders(
      <PropertySetterEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByText("Property Setters")).toBeInTheDocument();
  });

  it("shows no setters message when empty", () => {
    renderWithProviders(
      <PropertySetterEditor data={emptyConfig} onChange={onChange} />
    );
    expect(
      screen.getByText("No property setters configured")
    ).toBeInTheDocument();
  });

  it("shows add setter button", () => {
    renderWithProviders(
      <PropertySetterEditor data={emptyConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("add-setter-btn")).toBeInTheDocument();
    expect(screen.getByText("Add Setter")).toBeInTheDocument();
  });

  it("hides add setter button in readOnly mode", () => {
    renderWithProviders(
      <PropertySetterEditor data={emptyConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByTestId("add-setter-btn")).not.toBeInTheDocument();
  });

  it("calls onChange when add setter is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={emptyConfig} onChange={onChange} />
    );
    await user.click(screen.getByTestId("add-setter-btn"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        setOnActions: [
          expect.objectContaining({
            actions: [],
            setProperties: [expect.objectContaining({ name: "" })],
          }),
        ],
      })
    );
  });

  it("renders populated config with setter", () => {
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByTestId("setter-editor")).toBeInTheDocument();
  });

  it("shows action tags in setter", () => {
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("greet")).toBeInTheDocument();
    expect(screen.getByText("welcome")).toBeInTheDocument();
  });

  it("shows property rows with values", () => {
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    const rows = screen.getAllByTestId("property-row");
    expect(rows).toHaveLength(2);
    expect(screen.getByDisplayValue("user_greeted")).toBeInTheDocument();
    expect(screen.getByDisplayValue("lang")).toBeInTheDocument();
  });

  it("shows scope select", () => {
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    const selects = screen.getAllByTestId("scope-select");
    expect(selects.length).toBeGreaterThanOrEqual(1);
  });

  it("shows trigger actions and properties headings", () => {
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText("Trigger Actions")).toBeInTheDocument();
    expect(screen.getByText("Properties")).toBeInTheDocument();
  });

  it("shows props count badge", () => {
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByText(/2.*props/)).toBeInTheDocument();
  });

  // ─── Interaction tests ──────────────────────────────────────────────

  it("collapses/expands setter when toggle is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );

    const toggleBtn = screen.getByLabelText("Toggle property group");
    expect(screen.getByText("Trigger Actions")).toBeInTheDocument();

    // Collapse
    await user.click(toggleBtn);
    expect(screen.queryByText("Trigger Actions")).not.toBeInTheDocument();

    // Expand
    await user.click(toggleBtn);
    expect(screen.getByText("Trigger Actions")).toBeInTheDocument();
  });

  it("removes setter when remove button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByLabelText("Remove"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ setOnActions: [] })
    );
  });

  it("does not show remove button in readOnly mode", () => {
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} readOnly />
    );
    expect(screen.queryByLabelText("Remove")).not.toBeInTheDocument();
  });

  it("adds a new action tag via text input", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    const input = screen.getByPlaceholderText("e.g. greet, fallback");
    await user.type(input, "newAction{Enter}");
    expect(onChange).toHaveBeenCalled();
  });

  it("removes an action tag when its remove button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    // Find the remove button next to "greet" 
    await user.click(screen.getByLabelText("Remove greet"));
    expect(onChange).toHaveBeenCalled();
  });

  it("shows no actions message when setter has no actions", () => {
    const config: PropertySetterConfig = {
      setOnActions: [
        { actions: [], setProperties: [] },
      ],
    };
    renderWithProviders(
      <PropertySetterEditor data={config} onChange={onChange} />
    );
    expect(screen.getByText("No actions")).toBeInTheDocument();
  });

  it("shows no properties message when setter has no properties", () => {
    const config: PropertySetterConfig = {
      setOnActions: [
        { actions: ["test"], setProperties: [] },
      ],
    };
    renderWithProviders(
      <PropertySetterEditor data={config} onChange={onChange} />
    );
    expect(screen.getByText("No properties")).toBeInTheDocument();
  });

  it("adds a property row when Add Property is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    await user.click(screen.getByText("Add Property"));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({
        setOnActions: [
          expect.objectContaining({
            setProperties: expect.arrayContaining([
              expect.objectContaining({ name: "", valueString: "", scope: "conversation" }),
            ]),
          }),
        ],
      })
    );
  });

  it("changes scope via select", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    const selects = screen.getAllByTestId("scope-select");
    await user.selectOptions(selects[0]!, "longTerm");
    expect(onChange).toHaveBeenCalled();
  });

  it("renders multiple setters", () => {
    renderWithProviders(
      <PropertySetterEditor data={multiSetterConfig} onChange={onChange} />
    );
    const editors = screen.getAllByTestId("setter-editor");
    expect(editors).toHaveLength(2);
  });

  it("shows fromObjectPath when populated", () => {
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    expect(screen.getByDisplayValue("properties.language")).toBeInTheDocument();
  });

  it("shows override checkbox and can toggle it", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );
    const overrideLabels = screen.getAllByText("Override");
    expect(overrideLabels.length).toBeGreaterThanOrEqual(1);

    // Click the first override checkbox
    const checkbox = within(overrideLabels[0]!.closest("label")!).getByRole("checkbox");
    await user.click(checkbox);
    expect(onChange).toHaveBeenCalled();
  });

  it("opens value editor modal when expand button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );

    const expandBtn = screen.getAllByTitle("Edit in editor")[0];
    await user.click(expandBtn!);

    // Modal should appear with the Edit Value title
    expect(screen.getByText(/Edit Value/)).toBeInTheDocument();
    expect(screen.getByTestId("mock-monaco")).toBeInTheDocument();
  });

  it("closes value editor modal when Cancel is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );

    // Open modal
    const expandBtn = screen.getAllByTitle("Edit in editor")[0];
    await user.click(expandBtn!);

    // Close modal
    await user.click(screen.getByText("Cancel"));

    expect(screen.queryByText(/Edit Value/)).not.toBeInTheDocument();
  });

  it("applies value from editor modal when Apply is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );

    // Open modal
    const expandBtn = screen.getAllByTitle("Edit in editor")[0];
    await user.click(expandBtn!);

    // Click apply
    await user.click(screen.getByText("Apply"));

    expect(onChange).toHaveBeenCalled();
    // Modal should close
    expect(screen.queryByText(/Edit Value/)).not.toBeInTheDocument();
  });

  it("shows (no actions) text when setter has no actions in header", () => {
    const config: PropertySetterConfig = {
      setOnActions: [
        { actions: [], setProperties: [{ name: "p", valueString: "v", scope: "step", override: true }] },
      ],
    };
    renderWithProviders(
      <PropertySetterEditor data={config} onChange={onChange} />
    );
    expect(screen.getByText("(no actions)")).toBeInTheDocument();
  });

  it("removes property row when trash button is clicked", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <PropertySetterEditor data={populatedConfig} onChange={onChange} />
    );

    const rows = screen.getAllByTestId("property-row");
    // Find the trash button in the first property row
    const trashBtns = within(rows[0]!).getAllByRole("button");
    // The last button in the row is the trash button
    const trashBtn = trashBtns[trashBtns.length - 1];
    await user.click(trashBtn!);
    expect(onChange).toHaveBeenCalled();
  });
});
