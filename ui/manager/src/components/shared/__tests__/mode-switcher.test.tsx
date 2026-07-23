import { describe, expect, it, vi, beforeEach } from "vitest";
import { screen, within } from "@testing-library/react";
import { renderWithProviders, userEvent } from "@/test/test-utils";
import { ModeSwitcher } from "@/components/shared/mode-switcher";

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async () => {
  const actual = await vi.importActual("react-router-dom");
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useLocation: () => ({ pathname: "/manage/agents" }),
  };
});

describe("ModeSwitcher", () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    localStorage.clear();
  });

  it("renders the trigger button", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    const trigger = screen.getByRole("button", { name: /switch workspace/i });
    expect(trigger).toBeInTheDocument();
  });

  it("shows 'Manager' label when expanded and on /manage route", () => {
    renderWithProviders(<ModeSwitcher collapsed={false} />);
    expect(screen.getByText("Manager")).toBeInTheDocument();
  });

  it("hides label text when collapsed", () => {
    renderWithProviders(<ModeSwitcher collapsed={true} />);
    // When collapsed, the text label is not rendered (icon-only)
    expect(screen.queryByText("Manager")).not.toBeInTheDocument();
    // But the trigger is still accessible via aria-label
    expect(screen.getByRole("button", { name: /switch workspace/i })).toBeInTheDocument();
  });

  it("opens dropdown on click and shows both options", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    await user.click(screen.getByRole("button", { name: /switch workspace/i }));
    const menu = screen.getByRole("menu");
    expect(menu).toBeInTheDocument();

    const items = within(menu).getAllByRole("menuitem");
    expect(items).toHaveLength(2);
  });

  it("closes dropdown on Escape", async () => {
    const user = userEvent.setup();
    renderWithProviders(<ModeSwitcher collapsed={false} />);

    await user.click(screen.getByRole("button", { name: /switch workspace/i }));
    expect(screen.getByRole("menu")).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });
});
