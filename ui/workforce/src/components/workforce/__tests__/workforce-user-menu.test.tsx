import { describe, expect, it, vi } from "vitest";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthContext, GUEST_CONTEXT, type AuthContextValue } from "@eddi/app-kit/components/auth/auth-context";
import { WorkforceUserMenu } from "@/components/workforce/workforce-user-menu";
import { threadsStorageKey } from "@eddi/app-kit/lib/workforce-threads-storage";
import { renderHook, act } from "@testing-library/react";
import { useWorkforceThreads } from "@/hooks/use-workforce-threads";
import type { ReactNode } from "react";

const alice = { username: "alice", firstName: "Alice", lastName: "Example", email: "alice@example.test", fullName: "Alice Example" };

function withAuth(value: Partial<AuthContextValue>) {
  return ({ children }: { children: ReactNode }) => (
    <AuthContext.Provider value={{ ...GUEST_CONTEXT, ...value }}>{children}</AuthContext.Provider>
  );
}

describe("WorkforceUserMenu", () => {
  it("names the signed-in person and signs out", async () => {
    const logout = vi.fn();
    const Wrapper = withAuth({ method: "keycloak", user: alice, logout });
    render(<WorkforceUserMenu />, { wrapper: Wrapper });

    expect(screen.getByTestId("user-menu-initials")).toHaveTextContent("AE");
    await userEvent.click(screen.getByTestId("user-menu-trigger"));
    expect(await screen.findByTestId("user-menu-dropdown")).toHaveTextContent("Alice Example");
    await userEvent.click(screen.getByTestId("user-menu-logout"));
    expect(logout).toHaveBeenCalledTimes(1);
  });

  it("with authentication off there is nothing to sign out of: no menu", () => {
    render(<WorkforceUserMenu />, { wrapper: withAuth({ method: "none" }) });
    expect(screen.queryByTestId("user-menu-trigger")).toBeNull();
  });
});

describe("useWorkforceThreads — per principal", () => {
  const register = (user: typeof alice | null) => {
    const { result } = renderHook(() => useWorkforceThreads(), {
      wrapper: withAuth({ method: user ? "keycloak" : "none", user }),
    });
    return result;
  };

  it("one person's pointers are not the next person's", () => {
    localStorage.clear();
    const first = register(alice);
    act(() => first.current.registerThread({ memberId: "m1", memberName: "M", conversationId: "c-alice", boardId: "b1" }));
    expect(localStorage.getItem(threadsStorageKey("alice"))).toContain("c-alice");

    const second = register({ ...alice, username: "bob" });
    expect(second.current.getThread("b1", "m1")).toBeUndefined();
    expect(register(alice).current.getThread("b1", "m1")?.conversationId).toBe("c-alice");
  });

  it("drops the unscoped key older versions wrote, without resuming it", () => {
    localStorage.setItem(
      "workforce-threads",
      JSON.stringify([{ memberId: "m1", memberName: "M", conversationId: "c-old", boardId: "b1", lastActivity: 1 }]),
    );
    const hook = register(alice);
    expect(hook.current.getThread("b1", "m1")).toBeUndefined();
    expect(localStorage.getItem("workforce-threads")).toBeNull();
  });
});
