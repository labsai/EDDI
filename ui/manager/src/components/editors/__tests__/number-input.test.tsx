import { describe, it, expect, vi } from "vitest";
import { useState } from "react";
import { fireEvent, screen } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { NumberInput } from "@/components/editors/number-input";

/** A parent that stores what the field reports, like every editor does. */
function Harness({
  initial,
  emptyValue,
  onValue,
}: {
  initial: number | undefined;
  emptyValue?: number;
  onValue?: (v: number | undefined) => void;
}) {
  const [value, setValue] = useState<number | undefined>(initial);
  return (
    <>
      <NumberInput
        value={value}
        emptyValue={emptyValue}
        integer
        onChange={(v) => {
          setValue(v);
          onValue?.(v);
        }}
        data-testid="num"
      />
      <button type="button" onClick={() => setValue(42)}>
        reset
      </button>
    </>
  );
}

describe("NumberInput", () => {
  it("does not append to the fallback after the field is cleared (-1 then 8 is 8, not -18)", async () => {
    // The editors rendered `value={x ?? -1}` with `parseInt(v) || -1`:
    // clearing stored -1, -1 was rendered back, and the next key appended to it.
    const user = userEvent.setup();
    const onValue = vi.fn();
    renderWithProviders(<Harness initial={-1} emptyValue={-1} onValue={onValue} />);
    const input = screen.getByTestId("num");

    await user.clear(input);
    expect(input).toHaveValue(null);
    await user.type(input, "8");

    expect(input).toHaveValue(8);
    expect(onValue).toHaveBeenLastCalledWith(8);
  });

  it("reports emptyValue (or undefined) while the field is empty", async () => {
    const user = userEvent.setup();
    const onValue = vi.fn();
    renderWithProviders(<Harness initial={5} onValue={onValue} />);
    await user.clear(screen.getByTestId("num"));
    expect(onValue).toHaveBeenLastCalledWith(undefined);
  });

  it("shows the stored fallback once the user leaves an empty field", () => {
    renderWithProviders(<Harness initial={5} emptyValue={0} />);
    const input = screen.getByTestId("num");
    fireEvent.change(input, { target: { value: "" } });
    expect(input).toHaveValue(null);
    fireEvent.blur(input);
    expect(input).toHaveValue(0);
  });

  it("takes a value set from outside (a reset, a version switch)", async () => {
    const user = userEvent.setup();
    renderWithProviders(<Harness initial={5} />);
    await user.click(screen.getByText("reset"));
    expect(screen.getByTestId("num")).toHaveValue(42);
  });

  it("keeps decimals unless integer is set", () => {
    const onChange = vi.fn();
    renderWithProviders(<NumberInput value={1} onChange={onChange} data-testid="dec" />);
    fireEvent.change(screen.getByTestId("dec"), { target: { value: "2.5" } });
    expect(onChange).toHaveBeenLastCalledWith(2.5);
  });
});
