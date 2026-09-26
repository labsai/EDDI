import { describe, it, expect, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { RenamableKeyInput } from "../renamable-key-input";

describe("RenamableKeyInput", () => {
  const setup = () => {
    const onRename = vi.fn();
    render(<RenamableKeyInput value="arg1" onRename={onRename} isAvailable={() => true} />);
    const input = screen.getByDisplayValue("arg1");
    fireEvent.change(input, { target: { value: "query" } });
    return { onRename, input };
  };

  it("commits on Enter", () => {
    const { onRename, input } = setup();
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onRename).toHaveBeenCalledWith("query");
  });

  it("does not commit on the Enter that confirms an IME composition", () => {
    const { onRename, input } = setup();
    fireEvent.keyDown(input, { key: "Enter", isComposing: true });
    expect(onRename).not.toHaveBeenCalled();
  });

  it("does not commit on the IME process key when compositionend came first", () => {
    const { onRename, input } = setup();
    fireEvent.keyDown(input, { key: "Enter", keyCode: 229 });
    expect(onRename).not.toHaveBeenCalled();
    // The draft is kept: an ordinary Enter afterwards still commits it.
    fireEvent.keyDown(input, { key: "Enter" });
    expect(onRename).toHaveBeenCalledWith("query");
  });
});
