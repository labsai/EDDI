import { beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { ViewToggle } from "../view-toggle";
import { getStoredViewMode, setStoredViewMode } from "../view-mode";

// Mock i18n
vi.mock("react-i18next", () => ({
  useTranslation: () => ({ t: (key: string, fallback?: string) => fallback || key }),
}));

describe("ViewToggle", () => {
  it("renders card and list toggle buttons", () => {
    const onChange = vi.fn();
    render(<ViewToggle view="card" onChange={onChange} />);

    expect(screen.getByTestId("view-toggle")).toBeInTheDocument();
    expect(screen.getByTestId("view-toggle-card")).toBeInTheDocument();
    expect(screen.getByTestId("view-toggle-list")).toBeInTheDocument();
  });

  it("marks card button as checked when view is card", () => {
    render(<ViewToggle view="card" onChange={vi.fn()} />);
    expect(screen.getByTestId("view-toggle-card")).toHaveAttribute("aria-checked", "true");
    expect(screen.getByTestId("view-toggle-list")).toHaveAttribute("aria-checked", "false");
  });

  it("marks list button as checked when view is list", () => {
    render(<ViewToggle view="list" onChange={vi.fn()} />);
    expect(screen.getByTestId("view-toggle-card")).toHaveAttribute("aria-checked", "false");
    expect(screen.getByTestId("view-toggle-list")).toHaveAttribute("aria-checked", "true");
  });

  it("calls onChange with 'list' when list button is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ViewToggle view="card" onChange={onChange} />);

    await user.click(screen.getByTestId("view-toggle-list"));
    expect(onChange).toHaveBeenCalledWith("list");
  });

  it("calls onChange with 'card' when card button is clicked", async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<ViewToggle view="list" onChange={onChange} />);

    await user.click(screen.getByTestId("view-toggle-card"));
    expect(onChange).toHaveBeenCalledWith("card");
  });

  it("has proper ARIA radiogroup role", () => {
    render(<ViewToggle view="card" onChange={vi.fn()} />);
    expect(screen.getByRole("radiogroup")).toBeInTheDocument();
  });

  it("toggle buttons have radio role", () => {
    render(<ViewToggle view="card" onChange={vi.fn()} />);
    const radios = screen.getAllByRole("radio");
    expect(radios).toHaveLength(2);
  });
});

describe("getStoredViewMode / setStoredViewMode", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("returns 'card' as default when nothing stored", () => {
    expect(getStoredViewMode("test-page")).toBe("card");
  });

  it("returns the stored value after setting", () => {
    setStoredViewMode("test-page", "list");
    expect(getStoredViewMode("test-page")).toBe("list");
  });

  it("stores different values for different pages", () => {
    setStoredViewMode("agents", "list");
    setStoredViewMode("workflows", "card");
    expect(getStoredViewMode("agents")).toBe("list");
    expect(getStoredViewMode("workflows")).toBe("card");
  });

  it("returns 'card' for invalid stored value", () => {
    localStorage.setItem("eddi-view-mode-test", "invalid");
    expect(getStoredViewMode("test")).toBe("card");
  });
});
