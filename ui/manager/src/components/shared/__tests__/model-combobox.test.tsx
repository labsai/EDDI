import { useState } from "react";
import { describe, it, expect, vi } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ModelCombobox } from "@/components/shared/model-combobox";

const MODELS = ["claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-4-5", "claude-sonnet-4-6"];

function Host({
  initial = "",
  readOnly,
  onValue,
}: {
  initial?: string;
  readOnly?: boolean;
  onValue?: (v: string) => void;
}) {
  const [value, setValue] = useState(initial);
  return (
    <ModelCombobox
      value={value}
      onChange={(v) => {
        setValue(v);
        onValue?.(v);
      }}
      suggestions={MODELS}
      defaultModel="claude-sonnet-5-5"
      readOnly={readOnly}
      testId="model"
    />
  );
}

const optionTexts = () =>
  screen.getAllByTestId("model-option").map((o) => o.textContent?.replace(/default$/, "") ?? "");

describe("ModelCombobox", () => {
  /**
   * The bug this component exists for: a native datalist filtered by the
   * pre-filled default showed exactly one option, so the field looked as if
   * the provider offered a single model.
   */
  it("lists every suggestion when opened on a pre-filled value", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host initial="claude-sonnet-5-5" />);

    await user.click(screen.getByTestId("model"));

    expect(optionTexts()).toEqual(MODELS);
    expect(screen.queryByTestId("model-group-matching")).not.toBeInTheDocument();
    const current = screen.getAllByTestId("model-option")[0]!;
    expect(current).toHaveAttribute("aria-selected", "true");
    expect(screen.getByTestId("model")).toHaveAttribute("aria-activedescendant", current.id);
  });

  it("puts matches first and keeps the rest below a divider while typing", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.type(screen.getByTestId("model"), "sonnet");

    const matching = screen.getByTestId("model-group-matching");
    const others = screen.getByTestId("model-group-others");
    expect(within(matching).getAllByTestId("model-option").map((o) => o.textContent)).toEqual([
      "claude-sonnet-5-5default",
      "claude-sonnet-4-6",
    ]);
    expect(within(others).getAllByTestId("model-option").map((o) => o.textContent)).toEqual([
      "claude-opus-5-5",
      "claude-haiku-4-5",
    ]);
  });

  it("matches across separators, so `opus 5.5` finds claude-opus-5-5", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.type(screen.getByTestId("model"), "opus 5.5");

    expect(
      within(screen.getByTestId("model-group-matching")).getAllByTestId("model-option"),
    ).toHaveLength(1);
  });

  it("says a custom id is fine and still shows everything when nothing matches", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.type(screen.getByTestId("model"), "my-finetune");

    expect(screen.getByTestId("model-no-match")).toBeInTheDocument();
    expect(optionTexts()).toEqual(MODELS);
    expect(screen.getByTestId("model")).toHaveValue("my-finetune");
  });

  it("selects with the mouse and closes", async () => {
    const onValue = vi.fn();
    const user = userEvent.setup();
    renderWithProviders(<Host initial="claude-sonnet-5-5" onValue={onValue} />);

    await user.click(screen.getByTestId("model-toggle"));
    await user.click(screen.getByText("claude-haiku-4-5"));

    expect(onValue).toHaveBeenLastCalledWith("claude-haiku-4-5");
    expect(screen.getByTestId("model")).toHaveValue("claude-haiku-4-5");
    expect(screen.queryByTestId("model-listbox")).not.toBeInTheDocument();
  });

  it("navigates with the keyboard and closes on Escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host initial="claude-sonnet-5-5" />);
    const input = screen.getByTestId("model");

    input.focus();
    await user.keyboard("{ArrowDown}");
    expect(screen.getByTestId("model-listbox")).toBeInTheDocument();
    await user.keyboard("{ArrowDown}{ArrowDown}{Enter}");
    expect(input).toHaveValue("claude-haiku-4-5");
    expect(screen.queryByTestId("model-listbox")).not.toBeInTheDocument();

    await user.keyboard("{ArrowDown}");
    await user.keyboard("{Escape}");
    expect(screen.queryByTestId("model-listbox")).not.toBeInTheDocument();
    expect(input).toHaveValue("claude-haiku-4-5");
  });

  it("offers no list on a read-only field", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host initial="claude-sonnet-5-5" readOnly />);

    await user.click(screen.getByTestId("model"));

    expect(screen.queryByTestId("model-toggle")).not.toBeInTheDocument();
    expect(screen.queryByTestId("model-listbox")).not.toBeInTheDocument();
  });
});
