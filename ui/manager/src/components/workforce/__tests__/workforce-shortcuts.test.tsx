import { describe, it, expect, vi } from "vitest";
import { renderWithProviders } from "@/test/test-utils";
import { WorkforceShortcuts } from "@/components/workforce/workforce-shortcuts";

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useLocation: () => ({ pathname: "/workforce" }),
  };
});

describe("WorkforceShortcuts", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
  });

  it("renders nothing (returns null)", () => {
    const { container } = renderWithProviders(<WorkforceShortcuts />);
    expect(container.innerHTML).toBe("");
  });

  it("navigates to /workforce/new on 'n' key press", () => {
    renderWithProviders(<WorkforceShortcuts />);
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "n", bubbles: true }));
    expect(mockNavigate).toHaveBeenCalledWith("/workforce/new");
  });

  it("dispatches show-shortcuts event on '?' key press", () => {
    const handler = vi.fn();
    window.addEventListener("workforce:show-shortcuts", handler);
    renderWithProviders(<WorkforceShortcuts />);
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "?", bubbles: true }));
    expect(handler).toHaveBeenCalled();
    window.removeEventListener("workforce:show-shortcuts", handler);
  });

  it("does not fire shortcuts when modifier keys are held", () => {
    renderWithProviders(<WorkforceShortcuts />);
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "n", ctrlKey: true, bubbles: true }));
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("cleans up event listener on unmount", () => {
    const { unmount } = renderWithProviders(<WorkforceShortcuts />);
    unmount();
    window.dispatchEvent(new KeyboardEvent("keydown", { key: "n", bubbles: true }));
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
