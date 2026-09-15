import { useState } from "react";
import { describe, it, expect, vi } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ChipInput } from "@/components/shared/chip-input";

/** A host that owns both the values and the pending text, as a real form does. */
function Host({
  initial = [],
  splitOn,
  validate,
  onValues,
}: {
  initial?: string[];
  splitOn?: RegExp;
  validate?: (candidate: string) => string | null;
  onValues?: (values: string[]) => void;
}) {
  const [values, setValues] = useState(initial);
  const [pending, setPending] = useState("");
  return (
    <>
      <ChipInput
        values={values}
        onChange={(next) => {
          setValues(next);
          onValues?.(next);
        }}
        pending={pending}
        onPendingChange={setPending}
        validate={validate}
        splitOn={splitOn}
        inputLabel="Add a value"
        testId="chips"
      />
      <button type="button">elsewhere</button>
      <span data-testid="pending">{pending}</span>
    </>
  );
}

describe("ChipInput", () => {
  it("commits on Enter", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.type(screen.getByTestId("chips-input"), "alpha{Enter}");

    expect(screen.getByTestId("chips-item-alpha")).toBeInTheDocument();
    expect(screen.getByTestId("chips-input")).toHaveValue("");
  });

  it("commits on the Add button", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.type(screen.getByTestId("chips-input"), "alpha");
    await user.click(screen.getByTestId("chips-add"));

    expect(screen.getByTestId("chips-item-alpha")).toBeInTheDocument();
  });

  it("commits on blur, so tabbing away does not lose the text", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host />);

    await user.type(screen.getByTestId("chips-input"), "alpha");
    await user.tab();

    expect(screen.getByTestId("chips-item-alpha")).toBeInTheDocument();
  });

  it("splits a pasted, space-delimited entry", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host splitOn={/[\s,]+/} />);

    await user.type(screen.getByTestId("chips-input"), "read write{Enter}");

    expect(screen.getByTestId("chips-item-read")).toBeInTheDocument();
    expect(screen.getByTestId("chips-item-write")).toBeInTheDocument();
  });

  it("refuses an invalid entry and keeps the text for correction", async () => {
    const user = userEvent.setup();
    renderWithProviders(
      <Host validate={(candidate) => (candidate === "bad" ? "nope" : null)} />,
    );

    await user.type(screen.getByTestId("chips-input"), "bad{Enter}");

    expect(screen.queryByTestId("chips-item-bad")).not.toBeInTheDocument();
    // The text stays put — clearing it would destroy what the user must fix.
    expect(screen.getByTestId("chips-input")).toHaveValue("bad");
  });

  it("removes a value with its own button", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host initial={["alpha", "beta"]} />);

    await user.click(screen.getByTestId("chips-remove-alpha"));

    expect(screen.queryByTestId("chips-item-alpha")).not.toBeInTheDocument();
    expect(screen.getByTestId("chips-item-beta")).toBeInTheDocument();
  });

  it("removes the last value on Backspace in an empty box", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host initial={["alpha", "beta"]} />);

    await user.click(screen.getByTestId("chips-input"));
    await user.keyboard("{Backspace}");

    expect(screen.queryByTestId("chips-item-beta")).not.toBeInTheDocument();
    expect(screen.getByTestId("chips-item-alpha")).toBeInTheDocument();
  });

  it("leaves the values alone when Backspace has text to delete instead", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Host initial={["alpha"]} />);

    await user.type(screen.getByTestId("chips-input"), "x");
    await user.keyboard("{Backspace}");

    expect(screen.getByTestId("chips-item-alpha")).toBeInTheDocument();
  });

  it("names its input and hides the values from the caller's label", () => {
    renderWithProviders(<Host />);
    expect(screen.getByLabelText("Add a value")).toBeInTheDocument();
  });

  it("hides the editing affordances when read-only", () => {
    const onValues = vi.fn();
    renderWithProviders(
      <>
        <ChipInput
          values={["alpha"]}
          onChange={onValues}
          pending=""
          onPendingChange={() => {}}
          inputLabel="Add a value"
          testId="chips"
          readOnly
        />
      </>,
    );

    expect(screen.getByTestId("chips-item-alpha")).toBeInTheDocument();
    expect(screen.queryByTestId("chips-input")).not.toBeInTheDocument();
    expect(screen.queryByTestId("chips-remove-alpha")).not.toBeInTheDocument();
  });
});
