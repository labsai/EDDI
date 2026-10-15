import { describe, it, expect, vi, beforeAll, beforeEach } from "vitest";
import { screen } from "@testing-library/react";
import { renderWithProviders } from "@/test/test-utils";
import { AuthContext, GUEST_CONTEXT, type AuthContextValue } from "@/components/auth/auth-context";
import { CommandPalette } from "@/components/shared/command-palette";
import { useCommandPalette } from "@/hooks/use-command-palette";

beforeAll(() => {
  global.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  };
  Element.prototype.scrollIntoView = vi.fn();
});

function as(roles: string[]): AuthContextValue {
  return { ...GUEST_CONTEXT, method: "keycloak", roles, user: null };
}

function renderPalette(auth: AuthContextValue) {
  return renderWithProviders(
    <AuthContext.Provider value={auth}>
      <CommandPalette />
    </AuthContext.Provider>,
  );
}

/**
 * Recents live in localStorage per browser, not per user: a page opened in an
 * admin session was offered to the next user who signed in as an editor.
 */
describe("CommandPalette recents are role-aware", () => {
  beforeEach(() => {
    useCommandPalette.setState({
      isOpen: true,
      recentPages: [
        { path: "/manage/audit", label: "Audit Trail" },
        { path: "/manage/agents", label: "Agents" },
      ],
    });
  });

  it("does not offer a recent admin-only screen to an editor", () => {
    renderPalette(as(["eddi-editor"]));
    const recent = screen.getAllByRole("option").map((o) => o.getAttribute("data-value") ?? o.textContent ?? "");
    expect(recent.some((v) => v.includes("/manage/audit"))).toBe(false);
    expect(recent.some((v) => v.includes("recent") && v.includes("/manage/agents"))).toBe(true);
  });

  it("still offers it to an admin", () => {
    renderPalette(as(["eddi-admin"]));
    const recent = screen.getAllByRole("option").map((o) => o.getAttribute("data-value") ?? o.textContent ?? "");
    expect(recent.some((v) => v.includes("recent") && v.includes("/manage/audit"))).toBe(true);
  });
});
