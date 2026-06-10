import { describe, it, expect } from "vitest";
import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { renderPage } from "@/test/test-utils";
import { ResourceDetailPage } from "@/pages/resource-detail";

function renderPropertySetterPage(id = "res1") {
  return renderPage(
    `/manage/resources/propertysetter/${id}`,
    <ResourceDetailPage />,
    "/manage/resources/:type/:id"
  );
}

describe("Property Setter Editor", () => {
  // ─── Basic Rendering ─────────────────────────────────────────

  it("renders propertysetter editor", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByTestId("propertysetter-editor")).toBeInTheDocument();
    });
  });

  it("renders setter cards from mock data (4 action groups)", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      const setters = screen.getAllByTestId("setter-editor");
      expect(setters.length).toBe(4); // greet, lookup_order, escalate, farewell
    });
  });

  it("renders property rows from mock data", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("property-row").length).toBeGreaterThan(0);
    });
  });

  it("renders scope dropdown with correct value", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      const selects = screen.getAllByTestId("scope-select");
      expect(selects.length).toBeGreaterThan(0);
      // First property in first setter: user_greeted, scope=conversation
      expect((selects[0] as HTMLSelectElement).value).toBe("conversation");
    });
  });

  it("renders add setter button", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-setter-btn")).toBeInTheDocument();
    });
  });

  it("renders form tab as default", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByTestId("form-view")).toBeInTheDocument();
    });
  });

  it("shows action group titles from mock data", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      // "greet" appears both in setter header and as action tag, so use getAllByText
      expect(screen.getAllByText("greet").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("lookup_order").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("escalate").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("farewell").length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows property names from mock data", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByDisplayValue("user_greeted")).toBeInTheDocument();
      expect(screen.getByDisplayValue("greeting_count")).toBeInTheDocument();
    });
  });

  it("shows property values from mock data", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByDisplayValue("true")).toBeInTheDocument();
    });
  });

  // ─── Interaction tests ───────────────────────────────────────────────

  it("clicking add setter adds a new setter card", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-setter-btn")).toBeInTheDocument();
    });

    const initialSetters = screen.getAllByTestId("setter-editor").length;
    await user.click(screen.getByTestId("add-setter-btn"));

    await waitFor(() => {
      expect(screen.getAllByTestId("setter-editor").length).toBe(
        initialSetters + 1
      );
    });
  });

  it("changing scope dropdown updates the value", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("scope-select").length).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("scope-select")[0] as HTMLSelectElement;
    await user.selectOptions(select, "longTerm");

    await waitFor(() => {
      expect(select.value).toBe("longTerm");
    });
  });

  it("renders property rows with key and value inputs", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("property-row").length).toBeGreaterThan(0);
    });

    const row = screen.getAllByTestId("property-row")[0];
    const inputs = within(row).getAllByRole("textbox");
    expect(inputs.length).toBeGreaterThanOrEqual(2);
  });

  it("switches to JSON tab and shows JSON view", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByTestId("tab-json")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("tab-json"));

    await waitFor(() => {
      expect(screen.getByTestId("json-view")).toBeInTheDocument();
    });
  });

  it("editing property name updates the value", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByDisplayValue("user_greeted")).toBeInTheDocument();
    });

    const nameInput = screen.getByDisplayValue("user_greeted");
    await user.clear(nameInput);
    await user.type(nameInput, "new-prop-name");

    await waitFor(() => {
      expect((nameInput as HTMLInputElement).value).toBe("new-prop-name");
    });
  });

  it("marks dirty indicator when property is changed", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByDisplayValue("user_greeted")).toBeInTheDocument();
    });

    const nameInput = screen.getByDisplayValue("user_greeted");
    await user.clear(nameInput);
    await user.type(nameInput, "changed-prop");

    await waitFor(() => {
      expect(screen.getByTestId("dirty-indicator")).toBeInTheDocument();
    });
  });

  // ─── NEW: Coverage expansion tests ──────────────────────────────────

  it("collapses and expands a setter card", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("setter-editor").length).toBeGreaterThan(0);
    });

    // Find the toggle button (aria-expanded) in the first setter
    const setterEditor = screen.getAllByTestId("setter-editor")[0];
    const toggleBtn = within(setterEditor).getByRole("button", { expanded: true });

    // Collapse
    await user.click(toggleBtn);
    expect(toggleBtn.getAttribute("aria-expanded")).toBe("false");

    // The property rows inside this setter should be hidden
    // Re-expand
    await user.click(toggleBtn);
    expect(toggleBtn.getAttribute("aria-expanded")).toBe("true");
  });

  it("removes a setter card when delete button is clicked", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("setter-editor").length).toBe(4);
    });

    // Click the remove button on the first setter (scoped via within())
    const firstSetter = screen.getAllByTestId("setter-editor")[0];
    const removeButtons = within(firstSetter).getAllByRole("button", { name: /remove/i });
    await user.click(removeButtons[0]);

    await waitFor(() => {
      expect(screen.getAllByTestId("setter-editor").length).toBe(3);
    });
  });

  it("removes a property row from a setter", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("property-row").length).toBeGreaterThan(0);
    });

    const initialRows = screen.getAllByTestId("property-row").length;

    // Find and click a delete button inside a property row
    const firstRow = screen.getAllByTestId("property-row")[0];
    // The trash button is the last button in the row
    const deleteButtons = within(firstRow).getAllByRole("button");
    const deleteBtn = deleteButtons[deleteButtons.length - 1];
    await user.click(deleteBtn);

    await waitFor(() => {
      expect(screen.getAllByTestId("property-row").length).toBe(initialRows - 1);
    });
  });

  it("adds a property row within a setter", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("setter-editor").length).toBeGreaterThan(0);
    });

    const initialRows = screen.getAllByTestId("property-row").length;

    // Click "Add Property" button (it contains text "Add Property")
    const addPropBtns = screen.getAllByText("Add Property");
    await user.click(addPropBtns[0]);

    await waitFor(() => {
      expect(screen.getAllByTestId("property-row").length).toBe(initialRows + 1);
    });
  });

  it("changes scope from step to longTerm", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("scope-select").length).toBeGreaterThan(0);
    });

    const select = screen.getAllByTestId("scope-select")[0] as HTMLSelectElement;
    await user.selectOptions(select, "step");

    await waitFor(() => {
      expect(select.value).toBe("step");
    });
  });

  it("renders override checkbox and toggles it", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("property-row").length).toBeGreaterThan(0);
    });

    // Find the override checkbox in the first property row
    const firstRow = screen.getAllByTestId("property-row")[0];
    const overrideCheckbox = within(firstRow).getByRole("checkbox") as HTMLInputElement;
    expect(overrideCheckbox.checked).toBe(true); // Default is true in mock data

    await user.click(overrideCheckbox);

    await waitFor(() => {
      expect(overrideCheckbox.checked).toBe(false);
    });
  });

  it("shows prop count badge in setter header", async () => {
    renderPropertySetterPage();
    await waitFor(() => {
      // Multiple setters show prop counts - verify at least one "3 props" exists
      const badges = screen.getAllByText("3 props");
      expect(badges.length).toBeGreaterThanOrEqual(1);
    });
  });

  it("shows (no actions) label for new empty setter", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getByTestId("add-setter-btn")).toBeInTheDocument();
    });

    await user.click(screen.getByTestId("add-setter-btn"));

    await waitFor(() => {
      expect(screen.getByText("(no actions)")).toBeInTheDocument();
    });
  });

  it("removes an action tag from a setter", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByText("greet").length).toBeGreaterThanOrEqual(1);
    });

    // Find the "Remove greet" button within the first setter
    const firstSetter = screen.getAllByTestId("setter-editor")[0];
    const removeBtn = within(firstSetter).getByRole("button", { name: "Remove greet" });
    await user.click(removeBtn);

    // After removing, the action tag for "greet" inside the first setter should be gone
    // The header text will also change from "greet" to "(no actions)"
    await waitFor(() => {
      const firstSetterAfter = screen.getAllByTestId("setter-editor")[0];
      expect(within(firstSetterAfter).getByText("(no actions)")).toBeInTheDocument();
    });
  });

  it("editing fromObjectPath updates the field", async () => {
    const user = userEvent.setup();
    renderPropertySetterPage();
    await waitFor(() => {
      expect(screen.getAllByTestId("property-row").length).toBeGreaterThan(0);
    });

    // Find a fromObjectPath input placeholder
    const fromPathInputs = screen.getAllByPlaceholderText("From path");
    expect(fromPathInputs.length).toBeGreaterThan(0);

    await user.type(fromPathInputs[0], "result.data");

    await waitFor(() => {
      expect((fromPathInputs[0] as HTMLInputElement).value).toContain("result.data");
    });
  });
});
