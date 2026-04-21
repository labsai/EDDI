import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { QuickReplies } from "./QuickReplies";

describe("QuickReplies", () => {
  it("renders nothing when replies array is empty", () => {
    const { container } = render(<QuickReplies replies={[]} onSelect={vi.fn()} />);
    expect(container.firstChild).toBeNull();
  });

  it("renders buttons for each reply", () => {
    const replies = [{ value: "Yes" }, { value: "No" }, { value: "Maybe" }];
    render(<QuickReplies replies={replies} onSelect={vi.fn()} />);
    expect(screen.getByText("Yes")).toBeInTheDocument();
    expect(screen.getByText("No")).toBeInTheDocument();
    expect(screen.getByText("Maybe")).toBeInTheDocument();
  });

  it("calls onSelect with the value when clicked", () => {
    const onSelect = vi.fn();
    render(<QuickReplies replies={[{ value: "Hello" }]} onSelect={onSelect} />);
    fireEvent.click(screen.getByText("Hello"));
    expect(onSelect).toHaveBeenCalledWith("Hello");
  });

  it("renders the data-testid attribute", () => {
    render(<QuickReplies replies={[{ value: "Hi" }]} onSelect={vi.fn()} />);
    expect(screen.getByTestId("quick-replies")).toBeInTheDocument();
  });
});
